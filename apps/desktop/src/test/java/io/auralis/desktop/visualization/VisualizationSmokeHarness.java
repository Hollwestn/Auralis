package io.auralis.desktop.visualization;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.model.Track;
import io.auralis.desktop.playback.PlaybackService;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.transform.Rotate;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.image.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone GPU/render/audio integration process; synthesized signals are isolated under .runtime. */
public final class VisualizationSmokeHarness extends Application {
    private static boolean failed;
    private Path output, source;
    private VisualizerPane visualizer;
    private PlaybackService playback;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private int index;
    private long suspendedFrames;
    private final AtomicInteger spectrumCallbacks = new AtomicInteger();
    private long lowDetailVertices;
    @Override public void init() throws Exception {
        output = Path.of(System.getProperty("auralis.visualTestDirectory", ".runtime/visualization"));
        if (!output.toAbsolutePath().normalize().toString().contains(".runtime")) throw new IllegalArgumentException("Use isolated .runtime output");
        Files.createDirectories(output);
        var cover=new java.awt.image.BufferedImage(512,512,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)cover.setRGB(x,y,x<256?0xf02020:0x2030f0);
        ImageIO.write(cover,"png",output.resolve("artwork.png").toFile());
        source = output.resolve("signal.wav"); FfmpegService ffmpeg = new FfmpegService();
        FfmpegService.run(List.of(ffmpeg.executable(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=0.08*sin(2*PI*55*t)*(0.6+0.4*sin(2*PI*2*t))+0.06*sin(2*PI*440*t)+0.03*sin(2*PI*4400*t):s=48000:d=30", "-c:a", "pcm_s16le", "-y", source.toString()), java.time.Duration.ofSeconds(30));
    }
    @Override public void start(Stage stage) {
        visualizer = new VisualizerPane(); visualizer.setQuality(VisualizerPane.Quality.HIGH); stage.setScene(new Scene(visualizer, 960, 540)); stage.setTitle("Auralis • GPU visualization verification"); stage.show();
        playback = new PlaybackService(worker, output.resolve("cache")); playback.volumeProperty().set(0);
        playback.onError(error -> abort(new IllegalStateException(error))); playback.setSpectrumListener((timestamp, duration, magnitudes, phases) -> { spectrumCallbacks.incrementAndGet(); visualizer.acceptSpectrum(magnitudes, timestamp); });
        playback.playingProperty().addListener((o, a, b) -> visualizer.setPlaying(b));
        playback.restore(new Track("visual-signal", "Synthesized QA signal", "Auralis QA", "Tests", "", source, 30, ""), 0, true);
        System.out.println("AURALIS_SCENE3D_SUPPORTED=" + visualizer.isHardware3DSupported()); after(1000, this::checkClocks);
    }
    private void checkClocks(){
        double before=visualizer.animationTime();
        after(1000,()->{try{
            double live=visualizer.animationTime()-before;
            if(live<.48||live>1.)throw new IllegalStateException("Playing clock rate changed: "+live);
            playback.pause();double paused=visualizer.animationTime();
            after(1000,()->{try{
                double idle=visualizer.animationTime()-paused;
                if(idle<.07||idle>.28)throw new IllegalStateException("Idle rotation missing or too fast: "+idle);
                System.out.println("AURALIS_IDLE_ROTATION_OK liveSeconds="+live+" idleSeconds="+idle+" idleRate=.18 playingRate=.75");
                playback.toggle();nextMode();
            }catch(Throwable failure){abort(failure);}});
        }catch(Throwable failure){abort(failure);}});
    }
    private void nextMode() {
        try {
            if (index >= VisualizerPane.Mode.values().length) { checkQuality(); return; }
            VisualizerPane.Mode mode = VisualizerPane.Mode.values()[index++]; visualizer.setMode(mode);
            after(1200, () -> { try { capture(mode.name().toLowerCase(Locale.ROOT)); nextMode(); } catch (Throwable error) { abort(error); } });
        } catch (Throwable error) { abort(error); }
    }
    private int themeIndex;
    private javafx.scene.paint.Color originalCenter;
    private void checkQuality(){playback.pause();visualizer.setAnimationsEnabled(false);visualizer.setMode(VisualizerPane.Mode.BLACK_HOLE);after(200,this::nextTheme);}
    private void nextTheme(){
        var themes=io.auralis.desktop.ui.Theme.values();
        if(themeIndex==themes.length){var original=themes[0];visualizer.setTheme(original.accentColor(),original.secondaryColor(),original.backgroundColor());visualizer.setAnimationsEnabled(true);playback.toggle();checkShaderQuality();return;}
        var theme=themes[themeIndex++];visualizer.setTheme(theme.accentColor(),theme.secondaryColor(),theme.backgroundColor());
        after(250,()->{try{
            var image=visualizer.snapshot(null,null);var center=image.getPixelReader().getColor((int)(image.getWidth()/2),(int)(image.getHeight()/2));
            if(center.getRed()+center.getGreen()+center.getBlue()<.25)throw new IllegalStateException("Black Hole theme surface is black: "+theme);
            if(originalCenter==null)originalCenter=center;else if(Math.abs(center.getRed()-originalCenter.getRed())+Math.abs(center.getGreen()-originalCenter.getGreen())+Math.abs(center.getBlue()-originalCenter.getBlue())<.06)throw new IllegalStateException("Theme did not recolor Black Hole: "+theme);
            ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",output.resolve("theme-"+theme.name().toLowerCase(Locale.ROOT)+".png").toFile());
            System.out.println("AURALIS_NATIVE_THEME_OK "+theme.name()+" center="+center);nextTheme();
        }catch(Throwable error){abort(error);}});
    }
    private void checkShaderQuality() {
        visualizer.setMode(VisualizerPane.Mode.RESONANCE); visualizer.setQuality(VisualizerPane.Quality.LOW);
        after(600, () -> { try { capture("shader-low-detail"); lowDetailVertices = visualizer.getRenderPixelCount(); visualizer.setQuality(VisualizerPane.Quality.HIGH); after(600, () -> { try { capture("shader-high-detail"); if (visualizer.isHardware3DSupported() && visualizer.getRenderPixelCount() <= lowDetailVertices) throw new IllegalStateException("High quality did not increase GPU render resolution"); checkUltraHd(); } catch (Throwable error) { abort(error); } }); } catch (Throwable error) { abort(error); } });
    }
    private void checkUltraHd(){
        visualizer.setMode(VisualizerPane.Mode.BLACK_HOLE);visualizer.setUltraHd(true);
        long before=visualizer.getGpuFrames();waitForUltra(before,0);
    }
    private void waitForUltra(long before,int attempts){after(200,()->{try{
        var image=visualizer.renderedImage();
        if(visualizer.getGpuFrames()<=before||image==null||image.getWidth()!=3840||image.getHeight()!=2160){if(attempts>=50)throw new IllegalStateException("4K frame was not presented");waitForUltra(before,attempts+1);return;}
        ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",output.resolve("black-hole-4k.png").toFile());
        System.out.println("AURALIS_NATIVE_4K_OK width=3840 height=2160 pixels="+visualizer.getRenderPixelCount()+" gpuMs="+visualizer.getGpuFrameMillis());
        visualizer.setUltraHd(false);after(600,this::suspend);
    }catch(Throwable error){abort(error);}});}
    private long meshVertices(Node node) { long count = node instanceof MeshView view && view.getMesh() instanceof TriangleMesh mesh ? mesh.getPoints().size() / 3 : 0; if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) count += meshVertices(child); return count; }
    private void capture(String name) throws Exception {
        WritableImage image = visualizer.snapshot(null, null); PixelReader pixels = image.getPixelReader();
        int nonBackground = 0; var base = pixels.getColor(0, 0);
        for (int y = 10; y < image.getHeight() - 20; y += 5) for (int x = 10; x < image.getWidth() - 10; x += 5) {
            var color = pixels.getColor(x, y); if (Math.abs(color.getRed() - base.getRed()) + Math.abs(color.getGreen() - base.getGreen()) + Math.abs(color.getBlue() - base.getBlue()) > .05) nonBackground++;
        }
        if (nonBackground < 25) throw new IllegalStateException("Scene appears blank: " + name + " pixels=" + nonBackground);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output.resolve(name + ".png").toFile());
        if(visualizer.isHardware3DSupported()&&playback.playingProperty().get()){
            if(visualizer.getLastBass()<.03)throw new IllegalStateException("Real bass data missing: "+name);
            System.out.println("AURALIS_BASS_RESPONSE_OK "+name+" bass="+visualizer.getLastBass()+" gpuMs="+visualizer.getGpuFrameMillis()+" pixels="+visualizer.getRenderPixelCount());
        }
        System.out.println("AURALIS_VISUAL_MODE_OK " + name + " contrastingSamples=" + nonBackground + " frames=" + visualizer.getRenderedFrames());
    }
    private boolean interactionChecked;
    private void suspend() {
        if (!interactionChecked && visualizer.isHardware3DSupported()) { interactionChecked=true; checkInteraction(); return; }
        visualizer.setActive(false); suspendedFrames = visualizer.getRenderedFrames();
        after(500, () -> { try { if (visualizer.getRenderedFrames() != suspendedFrames) throw new IllegalStateException("Minimized scene continued rendering"); visualizer.setActive(true); visualizer.setReducedMotion(true); suspendedFrames = visualizer.getRenderedFrames(); after(800, this::finishChecks); } catch (Throwable error) { abort(error); } });
    }
    private void checkInteraction() {
        playback.pause();
        visualizer.setAnimationsEnabled(false);
        var before=visualizer.snapshot(null,null);
        javafx.event.Event.fireEvent(visualizer,new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_PRESSED,300,200,300,200,javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,true,null));
        var after=visualizer.snapshot(null,null);
        for(int y=0;y<before.getHeight();y+=8)for(int x=0;x<before.getWidth();x+=8)if(before.getPixelReader().getArgb(x,y)!=after.getPixelReader().getArgb(x,y))throw new IllegalStateException("Click changed the frozen scene");
        System.out.println("AURALIS_INTERACTION_OK clickEffects=false");
        visualizer.setAnimationsEnabled(true);suspend();
    }
    private void finishChecks() {
        try {
            if (spectrumCallbacks.get() < 12) throw new IllegalStateException("Real MediaPlayer spectrum callbacks missing");
            long reducedFrames = visualizer.getRenderedFrames() - suspendedFrames; if (reducedFrames > 14) throw new IllegalStateException("Reduced-motion frame cap exceeded: " + reducedFrames);
            visualizer.setEffectsEnabled(false); suspendedFrames = visualizer.getRenderedFrames();
            after(500, () -> { try { if (visualizer.getRenderedFrames() != suspendedFrames) throw new IllegalStateException("Disabled effects continued rendering"); visualizer.setAnimationsEnabled(false); visualizer.setEffectsEnabled(true); visualizer.setMode(VisualizerPane.Mode.BLACK_HOLE); visualizer.setMode(VisualizerPane.Mode.NEBULA_DRIFT); visualizer.setMode(VisualizerPane.Mode.PHOTON_TUNNEL); long frozen = visualizer.getRenderedFrames(); after(500, () -> { try { if (visualizer.getRenderedFrames() != frozen) throw new IllegalStateException("Animations-off scene continued rendering"); if(visualizer.isHardware3DSupported()&&visualizer.getPresentedMode()!=VisualizerPane.Mode.PHOTON_TUNNEL.ordinal())throw new IllegalStateException("Rapid paused mode changes lost the final choice"); capture("animations-off-static-tunnel"); System.out.println("AURALIS_LATEST_FRAME_OK pausedRapidSwitch=true"); System.out.println("AURALIS_VISUAL_SMOKE_OK modes=8 reducedFrames=" + reducedFrames + " scene3d=" + visualizer.isHardware3DSupported()); Platform.exit(); } catch (Throwable error) { abort(error); } }); } catch (Throwable error) { abort(error); } });
        } catch (Throwable error) { abort(error); }
    }
    private void after(int ms, Runnable action) { PauseTransition wait = new PauseTransition(Duration.millis(ms)); wait.setOnFinished(e -> action.run()); wait.play(); }
    private void abort(Throwable error) { failed = true; error.printStackTrace(); Platform.exit(); }
    @Override public void stop() { if (playback != null) playback.close(); if (visualizer != null) visualizer.close(); worker.shutdownNow(); }
    public static void main(String[] args) { launch(args); if (failed) System.exit(1); }
    public static final class Launcher { public static void main(String[] args) { VisualizationSmokeHarness.main(args); } }
}
