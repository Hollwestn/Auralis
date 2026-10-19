package io.auralis.desktop.ui;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Pos;
public final class Views {
    private Views(){}
    public static Label label(String text,String style){Label label=new Label(text);label.getStyleClass().add(style);label.setWrapText(true);return label;}
    public static Button button(String text,String style,Runnable action){Button button=new Button(text);button.getStyleClass().add(style);button.setOnAction(e->action.run());return button;}
    public static HBox row(Node...nodes){HBox row=new HBox(12,nodes);row.setAlignment(Pos.CENTER_LEFT);return row;}
    public static VBox column(Node...nodes){return new VBox(15,nodes);}
    public static VBox page(String title,String subtitle,Node...children){VBox page=new VBox(20);page.getStyleClass().add("page");page.getChildren().addAll(label(title,"title"),label(subtitle,"subtitle"));page.getChildren().addAll(children);return page;}
    public static VBox card(Node...children){VBox card=new VBox(13,children);card.getStyleClass().add("card");return card;}
    public static ScrollPane scroll(Node content){ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);return scroll;}
    public static Region spacer(){Region region=new Region();HBox.setHgrow(region,Priority.ALWAYS);return region;}
}
