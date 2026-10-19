package io.auralis.desktop.ui;

import io.auralis.core.model.*;
import io.auralis.core.recommendation.RecommendationService;
import io.auralis.desktop.visualization.VisualizerPane;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import static io.auralis.desktop.ui.Views.*;

/** Dashboard composition uses only the current catalog, preferences, and actual listening history. */
public final class DashboardView {
    public record Data(List<Track> tracks,List<Track> recent,List<RecommendationService.Recommendation> recommendations,List<Playlist> playlists,Set<String> favorites){}
    public record Actions(Consumer<List<Track>> play,Runnable wave,Runnable waveSettings,Runnable library,Runnable favorites,Runnable importFolder,Runnable createPlaylist,Consumer<String> openPlaylist,Consumer<VisualizerPane.Mode> selectMode){}
    public static VBox build(Data data,Actions actions,VisualizerPane visualizer,ReadOnlyBooleanProperty playing,VisualizerPane.Mode mode,String greeting){
        FlowPane tabs=new FlowPane(9,8);Button current=button("Для вас","pill",()->{});current.getStyleClass().add("active");tabs.getChildren().addAll(current,button("Медиатека","pill",actions.library),button("Любимое","pill",actions.favorites));
        VBox page=new VBox(18);page.getStyleClass().add("page");Label greetingLabel=label(greeting,"title");greetingLabel.setStyle("-fx-font-size:25px;");page.getChildren().addAll(row(greetingLabel,spacer(),label("ВАША ЛИЧНАЯ ВСЕЛЕННАЯ","eyebrow")),tabs);
        Button start=button(data.tracks.isEmpty()?"Добавить музыку":"Слушать мою волну","primary",data.tracks.isEmpty()?actions.importFolder:actions.wave);start.setGraphic(Icons.of(data.tracks.isEmpty()?"plus":"play",16));
        Label heroTitle=label("Ваша музыка.\nНовая орбита.","hero-title");heroTitle.setWrapText(false);heroTitle.setStyle("-fx-font-size:30px;");
        VBox copy=column(label("MY WAVE / ПЕРСОНАЛЬНЫЙ ЭФИР","hero-kicker"),heroTitle,label(data.tracks.isEmpty()?"Соберите свою коллекцию.\nОткройте её новое звучание.":"Знакомое. Неожиданное. Ваше.\nПоток музыки, который меняется вместе с вами.","hero-note"),row(start));copy.getStyleClass().add("hero-copy");copy.setPrefWidth(325);copy.setMinWidth(310);copy.setMaxWidth(335);
        Button tune=button("Настроить волну  →","text-button",actions.waveSettings);copy.getChildren().add(tune);
        Label signal=label("","visual-status");signal.textProperty().bind(Bindings.when(playing).then("РЕАГИРУЕТ НА ВАШУ МУЗЫКУ").otherwise("ВКЛЮЧИТЕ МУЗЫКУ"));
        page.getChildren().add(cinematic(visualizer,copy,signal));
        ComboBox<VisualizerPane.Mode> modes=new ComboBox<>();modes.getItems().addAll(VisualizerPane.Mode.values());modes.setValue(mode);modes.setConverter(new javafx.util.StringConverter<>(){public String toString(VisualizerPane.Mode value){return modeName(value);}public VisualizerPane.Mode fromString(String value){return mode;}});modes.valueProperty().addListener((o,a,b)->actions.selectMode.accept(b));
        page.getChildren().add(row(label("Атмосфера","muted"),modes,spacer(),label(data.tracks.size()+" треков  ·  "+data.favorites.size()+" любимых","micro")));
        if(data.tracks.isEmpty()){
            VBox empty=card(label("Начните с того, что любите","section-title"),label("Выберите папку с музыкой. Оригиналы останутся на месте, а библиотека будет доступна без интернета.","subtitle"),row(button("Выбрать папку","secondary",actions.importFolder),button("Создать плейлист","text-button",actions.createPlaylist)));page.getChildren().add(empty);return page;
        }
        List<Track> recent=data.recent.isEmpty()?data.tracks:data.recent;String caption=data.recent.isEmpty()?"Ваша коллекция":"Продолжить слушать";
        page.getChildren().add(row(label(caption,"section-title"),spacer(),button("Все треки  →","section-action",actions.library)));page.getChildren().add(trackCards(recent.stream().limit(5).toList(),actions.play));
        if(data.tracks.size()>5&&!data.recommendations.isEmpty()){
            page.getChildren().add(row(label("На вашей частоте","section-title"),spacer(),button("Моя волна  →","section-action",actions.waveSettings)));
            page.getChildren().add(trackCards(data.recommendations.stream().limit(5).map(RecommendationService.Recommendation::track).toList(),actions.play));
        }
        page.getChildren().add(row(label("Ваши плейлисты","section-title"),spacer(),button("Создать  +","section-action",actions.createPlaylist)));
        FlowPane lists=new FlowPane(12,12);for(Playlist playlist:data.playlists.stream().limit(4).toList()){
            VBox tile=new VBox(9,Icons.of("music",23),label(playlist.name(),"tile-title"),label(playlist.tracks().size()+" треков","tile-subtitle"));tile.getStyleClass().add("playlist-card");tile.setPrefWidth(174);tile.setOnMouseClicked(e->actions.openPlaylist.accept(playlist.id()));lists.getChildren().add(tile);
        }
        if(data.playlists.isEmpty())lists.getChildren().add(button("+  Новый плейлист","secondary",actions.createPlaylist));page.getChildren().add(lists);return page;
    }
    public static StackPane cinematic(VisualizerPane visualizer,Region copy,Label signal){
        Parent previous=visualizer.getParent();if(previous instanceof Pane parent)parent.getChildren().remove(visualizer);
        visualizer.setBackdropMode(true);Region shade=new Region();shade.getStyleClass().add("hero-shade");shade.setMouseTransparent(true);
        StackPane hero=new StackPane(visualizer,shade,copy);hero.setMinSize(0,510);hero.setPrefHeight(510);hero.getStyleClass().add("hero-cinematic");
        copy.setMinWidth(0);copy.setPrefWidth(280);copy.setMaxWidth(285);copy.setMaxHeight(Region.USE_PREF_SIZE);copy.setPickOnBounds(false);StackPane.setAlignment(copy,Pos.CENTER_LEFT);StackPane.setMargin(copy,new Insets(25,28,25,30));
        if(signal!=null){signal.setMouseTransparent(true);hero.getChildren().add(signal);StackPane.setAlignment(signal,Pos.BOTTOM_RIGHT);StackPane.setMargin(signal,new Insets(0,18,16,0));}
        Rectangle clip=new Rectangle();clip.widthProperty().bind(hero.widthProperty());clip.heightProperty().bind(hero.heightProperty());clip.setArcWidth(44);clip.setArcHeight(44);hero.setClip(clip);
        return hero;
    }
    public static String modeName(VisualizerPane.Mode mode){if(mode==null)return "Black Hole";StringBuilder value=new StringBuilder();for(String part:mode.name().split("_")){if(!value.isEmpty())value.append(' ');value.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));}return value.toString();}
    public static FlowPane trackCards(List<Track> tracks,Consumer<List<Track>> play){FlowPane cards=new FlowPane(10,15);for(Track track:tracks){StackPane art=artwork(track,138);Label title=label(track.title(),"tile-title"),artist=label(track.artist(),"tile-subtitle");title.setWrapText(false);artist.setWrapText(false);title.setMaxWidth(138);artist.setMaxWidth(138);VBox tile=new VBox(8,art,title,artist);tile.setPrefWidth(156);tile.setMaxWidth(156);tile.getStyleClass().add("track-card");tile.setOnMouseClicked(e->{List<Track> queue=new ArrayList<>(tracks);Collections.rotate(queue,-queue.indexOf(track));play.accept(queue);});Tooltip.install(tile,new Tooltip(track.title()+"\n"+track.artist()));cards.getChildren().add(tile);}return cards;}
    public static StackPane artwork(Track track,double size){return artwork(track.artworkPath(),size);}
    public static StackPane artwork(String path,double size){StackPane art=new StackPane();art.getStyleClass().add("artwork-tile");art.setMinSize(size,size);art.setPrefSize(size,size);art.setMaxSize(size,size);if(path!=null&&!path.isBlank()&&Files.isRegularFile(Path.of(path))){Image image=new Image(Path.of(path).toUri().toString(),size*2,size*2,true,true,true);ImageView view=new ImageView(image);view.setFitWidth(size);view.setFitHeight(size);view.setPreserveRatio(false);Rectangle clip=new Rectangle(size,size);clip.setArcWidth(24);clip.setArcHeight(24);view.setClip(clip);art.getChildren().add(view);}else{Label mark=label("a","cover-placeholder");mark.setStyle("-fx-font-size:"+Math.max(16,size*.38)+"px;");art.getChildren().add(mark);}return art;}
}
