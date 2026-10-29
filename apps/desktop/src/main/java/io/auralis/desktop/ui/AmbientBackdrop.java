package io.auralis.desktop.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.*;
import javafx.scene.effect.BlendMode;
import java.util.*;

/** Quiet particle network and optical impulses. Audio energy is taken from the current player. */
public final class AmbientBackdrop extends Canvas implements AutoCloseable {
    private Color accent=Color.web("#f8d34a"),secondary=Color.web("#ff9152");
    private double pointerX=.6,pointerY=.3,x=.6,y=.3,intensity=.5,energy,target,time;
    private boolean active=true,enabled=true,reduced,playing;
    private long last,lastSpectrum;
    private int quality=1,fps=30;
    private java.util.function.IntSupplier budget=()->0;
    public void setBudget(java.util.function.IntSupplier value){budget=value;}
    private final double[] px=new double[80],py=new double[80];
    private final AnimationTimer timer=new AnimationTimer(){@Override public void handle(long now){if(last!=0&&now-last<1_000_000_000L/Math.min(30,fps))return;double dt=last==0?.033:Math.min(.08,(now-last)/1e9);last=now;render(now,dt);}};
    public AmbientBackdrop(){setMouseTransparent(true);widthProperty().addListener((o,a,b)->paintStill());heightProperty().addListener((o,a,b)->paintStill());timer.start();}
    public void setTheme(Theme theme){accent=theme.accentColor();secondary=theme.secondaryColor();paintStill();}
    public void setPointer(double nx,double ny){pointerX=Math.clamp(nx,0,1);pointerY=Math.clamp(ny,0,1);}
    public void acceptSpectrum(float[] values){double sum=0;int count=Math.min(12,values.length);for(int i=0;i<count;i++)if(Float.isFinite(values[i]))sum+=Math.clamp((values[i]+70)/70,0,1);target=count==0?0:sum/count;lastSpectrum=System.nanoTime();}
    public void setPlaying(boolean value){playing=value;}
    public void setIntensity(double value){intensity=Math.clamp(value,0,2);}
    public void setQuality(int value){quality=Math.clamp(value,0,2);}
    public void setFps(int value){fps=Math.clamp(value,15,60);}
    public void setActive(boolean value){active=value;updateRunning();}
    public void setEnabled(boolean value){enabled=value;updateRunning();}
    public void setReducedMotion(boolean value){reduced=value;updateRunning();}
    private void updateRunning(){last=0;if(active&&enabled&&!reduced)timer.start();else{timer.stop();paintStill();}}
    private void paintStill(){draw(System.nanoTime(),0);}
    private void glow(double cx,double cy,double radius,Color color,double opacity){var g=getGraphicsContext2D();g.setFill(new RadialGradient(0,0,cx,cy,radius,false,CycleMethod.NO_CYCLE,new Stop(0,color.deriveColor(0,1,1,Math.clamp(opacity,0,.4))),new Stop(.38,color.deriveColor(0,1,1,Math.clamp(opacity*.24,0,.4))),new Stop(1,Color.TRANSPARENT)));g.fillRect(cx-radius,cy-radius,radius*2,radius*2);}
    private void render(long now,double dt){if(!active||!enabled||reduced)return;time+=dt;x+=(pointerX-x)*(1-Math.exp(-dt/1.1));y+=(pointerY-y)*(1-Math.exp(-dt/1.1));double input=playing&&now-lastSpectrum<500_000_000L?target:0;energy+=(input-energy)*(1-Math.exp(-dt/.5));draw(now,dt);}
    private void draw(long now,double dt){var g=getGraphicsContext2D();double w=getWidth(),h=getHeight();g.clearRect(0,0,w,h);if(!enabled||w<1||h<1)return;
        glow(w*(.5+x*.18),h*(.23+y*.12),Math.max(240,w*.7),accent,(.035+energy*.035)*intensity);
        int count=Math.max(12,(int)((quality==0?32:quality==1?56:80)*new double[]{1,.8,.6,.4}[Math.clamp(budget.getAsInt(),0,3)]));
        for(int i=0;i<count;i++){double a=i*2.3999632297;double nx=(i*.61803398875)%1,ny=(i*.41421356237)%1;px[i]=(nx*w+Math.sin(time*.055+i*1.7)*28+(x-.5)*9+w)%w;py[i]=(ny*h+Math.cos(time*.043+i*.93)*24+(y-.5)*8+h)%h;}
        double distance=Math.min(135,Math.max(90,w*.14));
        for(int i=0;i<count;i++)for(int j=i+1;j<count;j++){double dx=px[i]-px[j],dy=py[i]-py[j],d2=dx*dx+dy*dy;if(d2<distance*distance){double fade=1-Math.sqrt(d2)/distance;g.setStroke(accent.deriveColor(0,.55,1,Math.clamp(fade*fade*(.13+energy*.08)*intensity,0,.3)));g.setLineWidth(.7);g.strokeLine(px[i],py[i],px[j],py[j]);}}
        for(int i=0;i<count;i++){double size=1.3+(i%4)*.4;g.setFill(accent.deriveColor(0,.35,1,Math.clamp((.17+energy*.13)*intensity,0,.7)));g.fillOval(px[i],py[i],size,size);}
    }
    @Override public void close(){timer.stop();}
}
