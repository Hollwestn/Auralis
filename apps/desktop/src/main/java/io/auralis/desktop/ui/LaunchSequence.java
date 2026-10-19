package io.auralis.desktop.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.*;
import javafx.scene.text.*;

/** A short composited eclipse reveal, independent of audio and GPU initialization. */
public final class LaunchSequence extends StackPane {
    private final Canvas canvas = new Canvas();
    private final Color accent;
    private final Runnable completed;
    private boolean finished;
    private long began;
    private double progress;
    private final AnimationTimer animation = new AnimationTimer() {
        @Override public void handle(long now) {
            if (began == 0) began = now;
            progress = Math.min(1, (now - began) / 2_800_000_000.);
            paint();
            if (progress >= 1) finish();
        }
    };
    public LaunchSequence(Color accent, Runnable completed) {
        this.accent = accent; this.completed = completed;
        setStyle("-fx-background-color:#050609;");
        getChildren().add(canvas);setOnMouseClicked(event -> finish());
        setAccessibleText("Auralis. Нажмите, чтобы пропустить заставку.");
        sceneProperty().addListener((o, before, after) -> { if (after == null) animation.stop(); });
    }
    public void play() { animation.start(); }
    public void finish() { if (finished) return; finished = true; animation.stop(); completed.run(); }
    @Override protected void layoutChildren() { canvas.setWidth(getWidth());canvas.setHeight(getHeight());paint(); }
    private void paint() {
        var g=canvas.getGraphicsContext2D(); double w=canvas.getWidth(),h=canvas.getHeight();
        g.setGlobalAlpha(1);g.setFill(Color.web("#050609"));g.fillRect(0,0,w,h);
        double fade=Math.min(1,progress/.17)*Math.min(1,(1-progress)/.17);
        double ease=progress*progress*(3-2*progress),r=Math.min(w*.2,h*.26)*(.83+ease*.23),x=w*.5,y=h*.39;
        g.setGlobalAlpha(Math.max(0,fade));
        for(int i=0;i<96;i++) {
            double sx=(i*.7337)%1*w,sy=(i*.3771)%1*h;
            g.setFill(accent.deriveColor(0,.4,1,.13+(i%5)*.09));g.fillOval(sx,sy,i%9==0?2:1,i%9==0?2:1);
        }
        g.setFill(new RadialGradient(0,0,x,y,r*2.1,false,CycleMethod.NO_CYCLE,new Stop(0,accent.deriveColor(0,1,.7,.25)),new Stop(.52,accent.deriveColor(0,1,.6,.08)),new Stop(1,Color.TRANSPARENT)));
        g.fillRect(0,0,w,h);
        g.setFill(new RadialGradient(0,0,x,y,r*1.03,false,CycleMethod.NO_CYCLE,new Stop(0,Color.web("#050609")),new Stop(.93,Color.web("#050609")),new Stop(.965,accent),new Stop(.98,Color.web("#fff7df")),new Stop(1,Color.TRANSPARENT)));
        g.fillOval(x-r*1.04,y-r*1.04,r*2.08,r*2.08);
        g.setFill(Color.web("#050609"));g.fillOval(x-r*1.007,y-r*.98,r*2.014,r*2.014);
        g.setFill(new LinearGradient(.08*w,y,.92*w,y,false,CycleMethod.NO_CYCLE,new Stop(0,Color.TRANSPARENT),new Stop(.4,accent.deriveColor(0,1,1,.18)),new Stop(.5,Color.web("#fff5df")),new Stop(.6,accent.deriveColor(0,1,1,.18)),new Stop(1,Color.TRANSPARENT)));
        g.fillRect(w*.08,y,w*.84,1.3);
        g.setTextAlign(TextAlignment.CENTER);g.setTextBaseline(VPos.CENTER);
        g.setFill(Color.web("#9298a5"));g.setFont(Font.font("Segoe UI",11));g.fillText("Y O U R   S O U N D .   Y O U R   U N I V E R S E .",x,h*.68);
        g.setFill(Color.web("#f6f3ec"));g.setFont(Font.font("Segoe UI Light",FontWeight.LIGHT,Math.min(72,w*.06)));g.fillText("A U R A L I S",x,h*.76);
        g.setFill(accent);g.fillRect(x-22,h*.83,44,1);
        g.setFill(Color.web("#9298a5"));g.setFont(Font.font("Segoe UI",10));g.fillText("Н А С Т Р О Й Т Е С Ь   Н А   С В О Ю   О Р Б И Т У",x,h*.88);
        g.setGlobalAlpha(.65);g.setFont(Font.font("Segoe UI",10));g.fillText("Нажмите, чтобы продолжить",x,h-28);g.setGlobalAlpha(1);
    }
}
