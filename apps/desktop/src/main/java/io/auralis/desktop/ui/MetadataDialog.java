package io.auralis.desktop.ui;
import io.auralis.core.library.LibraryService;
import io.auralis.core.model.Track;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import static io.auralis.desktop.ui.Views.*;

/** Uses the application's existing dialog, fields and card styles. */
public final class MetadataDialog extends Dialog<MetadataDialog.Edit> {
 public record Edit(List<String> ids,String title,String artist,String album,String genre,Integer track,Integer disc,Path artwork){
  public void apply(LibraryService library)throws Exception{library.transaction(()->{for(String id:ids){Track old=library.track(id).orElseThrow();library.updateMetadata(id,title==null?old.title():title,artist==null?old.artist():artist,album==null?old.album():album,genre==null?old.genre():genre);var extra=library.extras(id);library.setTrackNumbers(id,track==null?extra.trackNumber():track,disc==null?extra.discNumber():disc);if(artwork!=null)library.setTrackArtwork(id,artwork);}});}
 }
 public MetadataDialog(Stage owner,Theme theme,List<Track> tracks,LibraryService library){
  initOwner(owner);setTitle("Метаданные и обложка");setHeaderText(tracks.size()==1?tracks.getFirst().title():"Групповая правка · "+tracks.size()+" треков");
  boolean batch=tracks.size()>1;Track first=tracks.getFirst();TextField title=new TextField(batch?"":first.title()),artist=new TextField(batch?"":first.artist()),album=new TextField(batch?"":first.album()),genre=new TextField(batch?"":first.genre());var extra=library.extras(first.id());TextField number=new TextField(batch?"":Integer.toString(extra.trackNumber())),disc=new TextField(batch?"":Integer.toString(extra.discNumber()));
  for(TextField field:List.of(title,artist,album,genre,number,disc))field.setPromptText(batch?"Пусто — оставить прежнее":"");
  javafx.scene.image.ImageView coverPreview=new javafx.scene.image.ImageView();coverPreview.setFitWidth(80);coverPreview.setFitHeight(80);coverPreview.setPreserveRatio(true);coverPreview.setAccessibleText("Предварительный просмотр обложки");
  Path[] artwork={null};Label cover=label("Обложка останется прежней","muted"),preview=label("","muted");
  Runnable update=()->preview.setText("Предварительный просмотр: "+(artist.getText().isBlank()?first.artist():artist.getText())+" — "+(title.getText().isBlank()?first.title():title.getText())+"\nАльбом: "+(album.getText().isBlank()?first.album():album.getText()));for(TextField field:List.of(title,artist,album,genre))field.textProperty().addListener((o,a,b)->update.run());update.run();
  Button pick=button("Выбрать обложку","secondary",()->{FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Обложка","*.png","*.jpg","*.jpeg"));var file=chooser.showOpenDialog(owner);if(file!=null){artwork[0]=file.toPath();cover.setText(file.getName());coverPreview.setImage(new javafx.scene.image.Image(file.toURI().toString(),160,160,true,true,true));}});
  VBox content=column(label("Изменения сохраняются в библиотеке. Запись в оригиналы — отдельное действие с полной резервной копией файла.","muted"),label("Название","muted"),title,label("Исполнитель","muted"),artist,label("Альбом","muted"),album,label("Жанр","muted"),genre,row(label("Трек","muted"),number,label("Диск","muted"),disc),row(pick,cover,coverPreview),preview);content.setPrefWidth(560);getDialogPane().setContent(content);getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,ButtonType.OK);getDialogPane().getStylesheets().add(io.auralis.desktop.AuralisApp.class.getResource("auralis.css").toExternalForm());theme.apply(getDialogPane());
  javafx.scene.Node apply=getDialogPane().lookupButton(ButtonType.OK);apply.addEventFilter(javafx.event.ActionEvent.ACTION,event->{try{parse(number.getText(),9999);parse(disc.getText(),999);if(!batch&&title.getText().isBlank())throw new IllegalArgumentException("Введите название");}catch(Exception e){preview.setText(e.getMessage());event.consume();}});
  setResultConverter(type->type!=ButtonType.OK?null:new Edit(tracks.stream().map(Track::id).toList(),text(title,batch),text(artist,batch),text(album,batch),text(genre,batch),parse(number.getText(),9999),parse(disc.getText(),999),artwork[0]));
 }
 private static String text(TextField field,boolean batch){return batch&&field.getText().isBlank()?null:field.getText().strip();}
 private static Integer parse(String text,int max){if(text.isBlank())return null;try{int n=Integer.parseInt(text.strip());if(n>=0&&n<=max)return n;}catch(NumberFormatException ignored){}throw new IllegalArgumentException("Номер должен быть от 0 до "+max);}
}

