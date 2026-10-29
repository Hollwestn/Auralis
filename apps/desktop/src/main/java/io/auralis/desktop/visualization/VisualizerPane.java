package io.auralis.desktop.visualization;

import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.image.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import java.util.Objects;

/** GPU scenes share the exact GLSL source with Android/iOS. FX only presents completed frames. */
public final class VisualizerPane extends StackPane implements AutoCloseable {
    public enum Mode { BLACK_HOLE, NEBULA_DRIFT, RESONANCE, PHOTON_TUNNEL, PRISM_BLOOM, MAGNETIC_FIELD, OCEAN_CAUSTICS, FRACTAL_CATHEDRAL }
    public static Mode parseMode(String value){
        if(java.util.Set.of("LIQUID_CORE","PARTICLE_UNIVERSE","AURORA","SPECTRUM_WAVES","REACTIVE_ALBUM_ART","MINIMAL_PULSE","AMBIENT_FLOW").contains(value))return Mode.BLACK_HOLE;
        return Mode.valueOf(value);
    }
    public enum Quality { LOW, MEDIUM, HIGH }
    private final AudioEnvelope audio=new AudioEnvelope();
    private final CinematicRenderer renderer=new CinematicRenderer();
    private final ImageView surface=new ImageView();
    private final Canvas fallback=new Canvas();
    private final Label notice=new Label();
    private Mode mode=Mode.BLACK_HOLE;
    private Quality quality=Quality.MEDIUM;
    private Color accent=Color.web("#f8d34a"),background=Color.web("#08090b");
    private Image artwork;
    private boolean active=true,playing,effectsEnabled=true,animationsEnabled=true,reducedMotion,closed,backdropMode,gpuFailed,ultraHd;
    private double intensity=1,time,zoom=1,lastBass,idleSpeed=1,playingSpeed=.75;
    private int fps=45;
    private boolean adaptive=true;
    private final FrameBudget budget=new FrameBudget();
    public void setAdaptive(boolean value){adaptive=value;}
    public int graphicsBudgetLevel(){return adaptive?budget.level():0;}
    private long lastFrame,nextFrameDue,renderedFrames;
    private final AnimationTimer timer=new AnimationTimer(){
        @Override public void handle(long now){
            int requested=adaptive?budget.fps(fps):fps;
            int cap=reducedMotion?Math.min(requested,15):playing?requested:Math.min(requested,15);
            long interval=1_000_000_000L/cap;
            if(nextFrameDue!=0&&now+500_000<nextFrameDue)return;
            nextFrameDue=nextFrameDue==0||now-nextFrameDue>interval*2?now+interval:nextFrameDue+interval;
            double dt=lastFrame==0?1./cap:Math.min(.1,(now-lastFrame)/1e9);lastFrame=now;
            time+=!reducedMotion?dt*(playing?playingSpeed:.18*idleSpeed):0;render(audio.step(dt));renderedFrames++;
        }
    };
    public VisualizerPane(){
        setPrefSize(420,350);setMinSize(80,80);setPickOnBounds(true);
        surface.setSmooth(true);surface.setPreserveRatio(false);surface.setMouseTransparent(true);
        fallback.setMouseTransparent(true);fallback.setVisible(false);
        notice.setMouseTransparent(true);notice.setStyle("-fx-font-size:11px;-fx-padding:8;");notice.setTextFill(Color.GRAY);
        StackPane.setAlignment(notice,Pos.BOTTOM_CENTER);getChildren().addAll(surface,fallback,notice);updateNotice();
        setOnScroll(e->{if(e.isControlDown()){zoom=Math.clamp(zoom+e.getDeltaY()*.0006,.8,1.3);renderStill();e.consume();}});
        setOnZoom(e->{zoom=Math.clamp(zoom*e.getZoomFactor(),.8,1.3);renderStill();e.consume();});
        sceneProperty().addListener((o,a,b)->{refreshTimer();if(b!=null)renderStill();});
    }
    @Override protected void layoutChildren(){super.layoutChildren();surface.setFitWidth(Math.max(1,getWidth()));surface.setFitHeight(Math.max(1,getHeight()));fallback.setWidth(Math.max(1,getWidth()));fallback.setHeight(Math.max(1,getHeight()));if(!animationsEnabled)renderStill();}
    public void acceptSpectrum(float[] magnitudes,double timestamp){audio.accept(magnitudes,timestamp);}
    public void setPlaying(boolean value){playing=value;audio.setPlaying(value);}
    public void setMode(Mode value){mode=Objects.requireNonNull(value);renderer.cancelQueued();renderStill();}
    public Mode getMode(){return mode;}
    public void setBackdropMode(boolean value){backdropMode=value;renderStill();}
    public void setQuality(Quality value){quality=Objects.requireNonNull(value);renderStill();}
    public Quality getQuality(){return quality;}
    public void setIntensity(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Intensity must be finite");intensity=Math.clamp(value,0,2);renderStill();}
    public void setSpeeds(double idle,double playing){
        if(!Double.isFinite(idle)||!Double.isFinite(playing))throw new IllegalArgumentException("Speed must be finite");
        idleSpeed=Math.clamp(idle,0,2);playingSpeed=Math.clamp(playing,0,2);
    }
    public double getIdleSpeed(){return idleSpeed;}
    public double getPlayingSpeed(){return playingSpeed;}
    public void setFps(int value){fps=Math.clamp(value,15,60);}
    public void setEffectsEnabled(boolean value){effectsEnabled=value;if(!value)renderer.cancelQueued();surface.setVisible(value&&!gpuFailed);fallback.setVisible(value&&gpuFailed);updateNotice();refreshTimer();if(value)renderStill();}
    public void setReducedMotion(boolean value){reducedMotion=value;}
    public void setAnimationsEnabled(boolean value){animationsEnabled=value;refreshTimer();renderStill();}
    public void setActive(boolean value){active=value;if(!value)renderer.cancelQueued();refreshTimer();}
    public void setArtwork(Image image){artwork=image;renderStill();}
    public void setTheme(Color accent,Color secondary,Color background){this.accent=Objects.requireNonNull(accent);this.background=Objects.requireNonNull(background);renderStill();}
    public boolean isHardware3DSupported(){return renderer.ready()&&!gpuFailed;}
    public long getRenderedFrames(){return renderedFrames;}
    public long getGpuFrames(){return renderer.frames();}
    public double getGpuFrameMillis(){return renderer.lastMillis();}
    public int getRenderPixelCount(){return renderer.pixelCount();}
    Image renderedImage(){return renderer.renderedImage();}
    public double getLastBass(){return lastBass;}
    double animationTime(){return time;}
    int getPresentedMode(){return renderer.presentedMode();}
    public void setUltraHd(boolean value){ultraHd=value;renderer.cancelQueued();renderStill();}
    public boolean isUltraHd(){return ultraHd;}
    /** Retained for source compatibility; the old mesh SubScene has been replaced by offscreen OpenGL. */
    public SubScene getRenderScene(){return null;}
    private void refreshTimer(){timer.stop();lastFrame=0;nextFrameDue=0;if(!closed&&active&&effectsEnabled&&animationsEnabled&&getScene()!=null)timer.start();}
    private void updateNotice(){notice.setText(!effectsEnabled?"Визуальные эффекты выключены":gpuFailed?"GPU недоступен · спектральный режим":"");notice.setVisible(!effectsEnabled||gpuFailed);}
    public void renderStill(){if(!closed&&active&&effectsEnabled&&getScene()!=null)render(audio.step(0));}
    private void render(AudioEnvelope.Frame frame){
        if(closed||!active||!effectsEnabled)return;
        lastBass=frame.bass();
        if(gpuFailed||Boolean.getBoolean("auralis.graphics.software")){gpuFailed=true;updateNotice();drawFallback(frame);return;}
        double w=Math.max(80,getWidth()),h=Math.max(80,getHeight());
        double scale=quality==Quality.LOW?.5:quality==Quality.MEDIUM?.72:1.;
        if(adaptive)scale*=budget.scale();
        scale=Math.min(scale,(mode==Mode.BLACK_HOLE&&quality==Quality.HIGH?1600:1100)/Math.max(w,h));int width=Math.max(64,(int)(w*scale)),height=Math.max(64,(int)(h*scale));
        if(ultraHd&&mode==Mode.BLACK_HOLE){double factor=adaptive?budget.scale():1;width=Math.max(64,(int)(3840*factor));height=Math.max(64,(int)(2160*factor));}
        float offset=backdropMode?(float)(w/h*(mode==Mode.BLACK_HOLE?.10:.19)):0;
        renderer.render(new CinematicRenderer.Frame(width,height,mode.ordinal(),ultraHd&&mode==Mode.BLACK_HOLE?2:quality.ordinal(),(float)time,(float)(intensity*(reducedMotion?.3:1)),accent,frame,offset,0,(float)zoom,(float)(w/Math.min(w,h)/(width/(double)Math.min(width,height))),(float)(h/Math.min(w,h)/(height/(double)Math.min(width,height))),artwork),image->{
            if(active&&effectsEnabled&&!closed){if(adaptive)budget.sample(renderer.lastMillis(),playing?budget.fps(fps):Math.min(budget.fps(fps),15),System.nanoTime());surface.setImage(image);surface.setVisible(true);fallback.setVisible(false);}
        },error->{gpuFailed=true;System.err.println("Auralis GPU fallback: "+error);surface.setVisible(false);fallback.setVisible(true);updateNotice();drawFallback(frame);});
    }
    private void drawFallback(AudioEnvelope.Frame frame){
        fallback.setVisible(true);surface.setVisible(false);var g=fallback.getGraphicsContext2D();double w=fallback.getWidth(),h=fallback.getHeight();g.setFill(background);g.fillRect(0,0,w,h);g.setFill(accent);
        for(int i=0;i<32;i++){double size=2+frame.bands()[i*2]*h*.55*intensity;g.fillRoundRect(i*w/32+2,h*.65-size/2,Math.max(1,w/32-4),size,3,3);}
    }
    @Override public void close(){closed=true;timer.stop();renderer.close();surface.setImage(null);}
}
