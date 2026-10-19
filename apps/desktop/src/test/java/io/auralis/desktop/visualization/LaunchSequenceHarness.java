package io.auralis.desktop.visualization;
import javafx.application.*;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.scene.paint.Color;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import java.nio.file.*;
import io.auralis.desktop.ui.LaunchSequence;
public final class LaunchSequenceHarness extends Application {
 public void start(Stage stage){
  long[] began={0};
  var intro=new LaunchSequence(Color.web("#F8D34A"),()->{double elapsed=(System.nanoTime()-began[0])/1e9;if(elapsed<2.5||elapsed>4)throw new IllegalStateException("Unexpected intro duration "+elapsed);System.out.println("AURALIS_INTRO_OK seconds="+elapsed);Platform.exit();});
  stage.setScene(new Scene(intro,1440,900));stage.show();began[0]=System.nanoTime();intro.play();
  var shot=new PauseTransition(Duration.millis(1400));shot.setOnFinished(e->{try{var out=Path.of(".runtime/cosmos-intro.png");ImageIO.write(SwingFXUtils.fromFXImage(intro.snapshot(null,null),null),"png",out.toFile());}catch(Exception failure){throw new RuntimeException(failure);}});shot.play();
 }
 public static final class Launcher {public static void main(String[] args){launch(LaunchSequenceHarness.class,args);}}
}
