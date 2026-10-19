package io.auralis.desktop.ui;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;

/** Code-native icons on a consistent 24-unit grid. */
public final class Icons {
    private Icons(){}
    public static StackPane of(String name){return of(name,19);}
    public static StackPane of(String name,double size){
        String shape=switch(name){
            case "home"->"M3 10L12 3L21 10V21H15V14H9V21H3Z";
            case "wave"->"M2 12H5L8 5L12 19L16 8L19 12H22";
            case "library"->"M4 4V20M9 4V20M15 5L20 19M3 20H21";
            case "heart"->"M12 21S2 15 2 8A5 5 0 0 1 12 6A5 5 0 0 1 22 8C22 15 12 21 12 21Z";
            case "history"->"M4 7A9 9 0 1 1 3 14M4 2V8H10M12 7V12L16 14";
            case "studio"->"M3 5H21V19H3ZM7 9V15M11 7V17M15 10V14M19 8V16";
            case "equalizer"->"M5 3V7M5 12V21M12 3V13M12 18V21M19 3V5M19 10V21M2 7H8V12H2ZM9 13H15V18H9ZM16 5H22V10H16Z";
            case "settings"->"M9 3H15L16 6L19 7L22 9V15L19 17L16 18L15 21H9L8 18L5 17L2 15V9L5 7L8 6ZM16 12A4 4 0 1 0 8 12A4 4 0 1 0 16 12";
            case "play"->"M7 3L21 12L7 21Z"; case "pause"->"M6 4H10V20H6ZM15 4H19V20H15Z";
            case "previous"->"M5 4V20M20 4L8 12L20 20Z"; case "next"->"M19 4V20M4 4L16 12L4 20Z";
            case "shuffle"->"M3 5H6C11 5 13 19 18 19H22M18 15L22 19L18 23M3 19H6C8 19 9 16 10 14M14 8C15 6 16 5 18 5H22M18 1L22 5L18 9";
            case "repeat"->"M4 8H21L17 4M21 8L17 12M20 16H3L7 20M3 16L7 12";
            case "plus"->"M12 4V20M4 12H20";case "folder"->"M3 6H10L12 9H21V20H3ZM3 6V4H10L12 6H21V9";
            case "search"->"M16 10A6 6 0 1 0 4 10A6 6 0 1 0 16 10M15 15L22 22";
            case "volume"->"M3 9H7L12 5V19L7 15H3ZM16 8C19 10 19 14 16 16M19 4C24 8 24 16 19 20";
            case "expand"->"M3 9V3H9M15 3H21V9M21 15V21H15M9 21H3V15";
            case "queue"->"M3 5H21M3 10H16M3 15H12M17 14L23 18L17 22Z";
            case "arrow"->"M4 12H21M14 5L21 12L14 19";
            case "music"->"M9 18V5L21 3V16M9 8L21 6M9 18A3 3 0 1 1 6 15C8 15 9 16 9 18M21 16A3 3 0 1 1 18 13C20 13 21 14 21 16";
            case "spark"->"M12 2L15 9L22 12L15 15L12 22L9 15L2 12L9 9Z";
            case "server"->"M3 3H21V10H3ZM3 14H21V21H3ZM7 6V7M7 17V18M12 6H17M12 17H17";
            default->"M5 12H19";
        };
        SVGPath path=new SVGPath();path.setContent(shape);path.setFill(Color.TRANSPARENT);path.setStrokeWidth(1.65);path.setStrokeLineCap(StrokeLineCap.ROUND);path.setStrokeLineJoin(StrokeLineJoin.ROUND);path.getStyleClass().add("glyph");
        if(name.equals("play")||name.equals("pause"))path.getStyleClass().add("glyph-solid");
        Group group=new Group(new Rectangle(24,24,Color.TRANSPARENT),path);group.setScaleX(size/24);group.setScaleY(size/24);
        StackPane pane=new StackPane(group);pane.setMinSize(size,size);pane.setPrefSize(size,size);pane.setMaxSize(size,size);pane.setMouseTransparent(true);return pane;
    }
}
