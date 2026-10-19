package io.auralis.desktop.ui;
import javafx.animation.*;
import javafx.scene.Node;
import javafx.util.Duration;
public final class Motion {
    private Motion(){}
    public static void reveal(Node node, boolean reduced) {
        if(reduced){node.setOpacity(1);node.setTranslateY(0);return;}
        node.setOpacity(0);node.setTranslateY(8);
        FadeTransition fade=new FadeTransition(Duration.millis(180),node);fade.setToValue(1);
        TranslateTransition move=new TranslateTransition(Duration.millis(220),node);move.setToY(0);move.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade,move).play();
    }
}
