package io.auralis.desktop;

import io.auralis.core.library.LibraryService;
import javafx.application.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.concurrent.*;

public final class AuralisApp extends Application {
    private MainController controller;
    private LibraryService library;
    private final ExecutorService worker=Executors.newVirtualThreadPerTaskExecutor();
    private static volatile boolean smokeFailed;
    public static Path dataDirectory(){String override=System.getProperty("auralis.data");if(override!=null)return Path.of(override);String local=System.getenv("LOCALAPPDATA");return Path.of(local==null?System.getProperty("user.home"):local,"AuralisData");}
    public static Path legacyDataDirectory(){String override=System.getProperty("auralis.legacyData");if(override!=null)return Path.of(override);String local=System.getenv("LOCALAPPDATA");return Path.of(local==null?System.getProperty("user.home"):local,"Auralis");}
    public static Path prepareDataDirectory()throws java.io.IOException{Path target=dataDirectory();if(System.getProperty("auralis.data")==null||System.getProperty("auralis.legacyData")!=null)DesktopDataMigration.migrateIfNeeded(legacyDataDirectory(),target);return target;}
    @Override public void init() throws Exception {library=new LibraryService(prepareDataDirectory());if(getParameters().getRaw().contains("--smoke-test")&&System.getProperty("auralis.fixture")!=null)library.importPaths(java.util.List.of(Path.of(System.getProperty("auralis.fixture"))));}
    @Override public void start(Stage stage)throws Exception{
        FXMLLoader loader=new FXMLLoader(AuralisApp.class.getResource("shell.fxml"));Parent root=loader.load();
        var layers=new javafx.scene.layout.StackPane(root);
        Scene scene=new Scene(layers,1440,900);scene.getStylesheets().add(AuralisApp.class.getResource("auralis.css").toExternalForm());
        stage.getIcons().add(new javafx.scene.image.Image(AuralisApp.class.getResourceAsStream("auralis-icon.png")));
        stage.setTitle("Auralis — музыка в вашем ритме");stage.setMinWidth(1040);stage.setMinHeight(690);stage.setScene(scene);
        controller=loader.getController();controller.attach(library,worker,stage);stage.show();
        scene.setOnKeyPressed(event->{if(event.isConsumed())return;if(event.getCode()==javafx.scene.input.KeyCode.F11){stage.setFullScreen(!stage.isFullScreen());event.consume();return;}Node target=event.getTarget() instanceof Node node?node:null;while(target!=null){if(target instanceof javafx.scene.control.TextInputControl||target instanceof javafx.scene.control.ButtonBase||target instanceof javafx.scene.control.ComboBoxBase<?>||target instanceof javafx.scene.control.Slider)return;target=target.getParent();}switch(event.getCode()){case SPACE->{controller.togglePlay();event.consume();}case RIGHT->{controller.seekRelative(5);event.consume();}case LEFT->{controller.seekRelative(-5);event.consume();}default->{}}});
        if(getParameters().getRaw().contains("--smoke-test")){
            var pause=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));pause.setOnFinished(e->{try{Path output=dataDirectory().resolve("smoke-home.png");ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",output.toFile());controller.smokeCheck();System.out.println("AURALIS_UI_SMOKE_OK "+output);Platform.exit();}catch(Throwable failure){smokeFailed=true;failure.printStackTrace();Platform.exit();}});pause.play();
        }else if(!Boolean.parseBoolean(library.setting("animations.enabled","true"))||Boolean.parseBoolean(library.setting("reducedMotion","false")))Platform.runLater(controller::firstRun);
        else {
            var intro=new io.auralis.desktop.ui.LaunchSequence(introAccent(),()->{layers.getChildren().removeIf(node->node instanceof io.auralis.desktop.ui.LaunchSequence);controller.firstRun();});
            layers.getChildren().add(intro);intro.play();
            scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,event->{if(layers.getChildren().contains(intro)){intro.finish();event.consume();}});
        }
    }
    private javafx.scene.paint.Color introAccent(){try{return io.auralis.desktop.ui.Theme.valueOf(library.setting("theme","OBSIDIAN_YELLOW")).accentColor();}catch(IllegalArgumentException failure){return io.auralis.desktop.ui.Theme.OBSIDIAN_YELLOW.accentColor();}}

    @Override public void stop()throws Exception{if(controller!=null)controller.close();worker.shutdown();if(!worker.awaitTermination(8,TimeUnit.SECONDS))worker.shutdownNow();if(library!=null)library.close();}
    public static void main(String[] args){launch(args);if(smokeFailed)System.exit(1);}
}
