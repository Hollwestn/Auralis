package io.auralis.desktop.ui;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import io.auralis.core.backup.LibraryArchive;
import io.auralis.core.library.LibraryService;
import io.auralis.core.model.*;
import io.auralis.core.transfer.PeerTransfer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import static io.auralis.desktop.ui.Views.*;

/** A temporary opt-in transfer window; no listener runs before the user starts a session. */
public final class PeerDialog extends Dialog<Void> {
 private PeerTransfer peer;private volatile boolean building,disposed;private String code="";
 public PeerDialog(Stage owner,Theme theme,LibraryService library,List<Track> tracks,List<Playlist> playlists,ExecutorService worker,Consumer<Path> receive){
  initOwner(owner);setTitle("Мои устройства");setHeaderText("Прямой обмен в общей Wi-Fi-сети");getDialogPane().getButtonTypes().add(ButtonType.CLOSE);getDialogPane().getStylesheets().add(io.auralis.desktop.AuralisApp.class.getResource("auralis.css").toExternalForm());theme.apply(getDialogPane());
  List<InetAddress> addresses;try{addresses=PeerTransfer.localAddresses();}catch(Exception e){addresses=List.of();}
  ComboBox<InetAddress> network=new ComboBox<>(FXCollections.observableArrayList(addresses));network.setConverter(new javafx.util.StringConverter<>(){public String toString(InetAddress a){return a==null?"":a.getHostAddress();}public InetAddress fromString(String s){return null;}});if(!addresses.isEmpty())network.setValue(addresses.getFirst());network.setAccessibleText("Адрес компьютера в локальной сети");
  Label status=label(addresses.isEmpty()?"Подключитесь к Wi-Fi или локальной сети. Публичные адреса не используются.":"Нажмите «Начать сеанс», затем отсканируйте QR на телефоне.","muted");status.setWrapText(true);
  ImageView qr=new ImageView();qr.setFitWidth(250);qr.setFitHeight(250);qr.setPreserveRatio(true);qr.setAccessibleText("QR-код защищённого подключения; также доступна кнопка копирования кода");
  Set<String> selected=new LinkedHashSet<>(),selectedLists=new LinkedHashSet<>();
  ListView<Track> songs=new ListView<>(FXCollections.observableArrayList(tracks));songs.setPrefHeight(180);songs.setCellFactory(list->new ListCell<>(){@Override protected void updateItem(Track track,boolean empty){super.updateItem(track,empty);if(empty||track==null){setGraphic(null);return;}CheckBox choice=new CheckBox(track.artist()+" — "+track.title());choice.setSelected(selected.contains(track.id()));choice.setOnAction(e->{if(choice.isSelected())selected.add(track.id());else selected.remove(track.id());});setGraphic(choice);}});
  ListView<Playlist> lists=new ListView<>(FXCollections.observableArrayList(playlists));lists.setPrefHeight(Math.min(110,Math.max(40,playlists.size()*35)));lists.setCellFactory(list->new ListCell<>(){@Override protected void updateItem(Playlist playlist,boolean empty){super.updateItem(playlist,empty);if(empty||playlist==null){setGraphic(null);return;}CheckBox choice=new CheckBox(playlist.name()+" · "+playlist.tracks().size());choice.setSelected(selectedLists.contains(playlist.id()));choice.setOnAction(e->{if(choice.isSelected())selectedLists.add(playlist.id());else selectedLists.remove(playlist.id());});setGraphic(choice);}});
  Button start=button("Начать сеанс","primary",()->{
   if(peer!=null)peer.close();try{
    peer=new PeerTransfer(network.getValue(),library.dataDirectory().resolve("peer-transfer"),Duration.ofMinutes(15),request->Platform.runLater(()->{
     if(disposed||peer==null)return;Alert alert=new Alert(Alert.AlertType.CONFIRMATION,"Устройство: "+request.device()+"\nКод сверки: "+request.code()+"\nПодтвердите только своё устройство с тем же кодом.",ButtonType.CANCEL,ButtonType.OK);alert.initOwner(getDialogPane().getScene().getWindow());alert.setTitle("Подключить телефон");alert.setHeaderText("Сравните код на двух устройствах");alert.getDialogPane().getStylesheets().setAll(getDialogPane().getStylesheets());theme.apply(alert.getDialogPane());if(alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK){peer.approve(request.id());status.setText("Телефон подтверждён. Выберите треки или плейлисты для отправки.");}else peer.reject(request.id());
    }),incoming->Platform.runLater(()->{if(!disposed){status.setText("Получено "+incoming.preview().tracks()+" треков. Просмотрите содержимое перед добавлением.");receive.accept(incoming.archive());}}));
    code=peer.pairingCode();var matrix=new QRCodeWriter().encode(code,BarcodeFormat.QR_CODE,320,320,Map.of(EncodeHintType.MARGIN,2));WritableImage image=new WritableImage(matrix.getWidth(),matrix.getHeight());for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)image.getPixelWriter().setArgb(x,y,matrix.get(x,y)?0xff08090b:0xffffffff);qr.setImage(image);status.setText("Сеанс открыт на 15 минут. Телефон: Настройки → Мои устройства → Сканировать QR.");
   }catch(Exception e){status.setText("Не удалось начать обмен: "+e.getMessage());}
  });start.setDisable(addresses.isEmpty());
  Button share=button("Подготовить выбранное для телефона","secondary",()->{
   if(peer==null){status.setText("Сначала начните сеанс.");return;}if(building)return;Set<String> ids=new LinkedHashSet<>(selected),playlistIds=Set.copyOf(selectedLists);for(Playlist p:playlists)if(playlistIds.contains(p.id()))p.tracks().forEach(t->ids.add(t.id()));if(ids.isEmpty()){status.setText("Выберите треки или плейлисты.");return;}
   PeerTransfer session=peer;building=true;status.setText("Подготовка выбранной музыки…");worker.submit(()->{try{Path file=library.dataDirectory().resolve("peer-transfer").resolve("outgoing-"+UUID.randomUUID()+".auralis");LibraryArchive.write(library,file,true,ids,playlistIds);session.share(file);Platform.runLater(()->status.setText("Музыка готова. На телефоне нажмите «Получить выбранное с компьютера»."));}catch(Exception e){Platform.runLater(()->status.setText("Не удалось подготовить передачу: "+e.getMessage()));}finally{building=false;}});
  });
  Button copy=button("Скопировать код вместо QR","text-button",()->{if(code.isBlank())return;ClipboardContent content=new ClipboardContent();content.putString(code);Clipboard.getSystemClipboard().setContent(content);status.setText("Код скопирован. Вставьте его в Auralis на своём телефоне.");});
  VBox left=column(label("Подключение","section-title"),network,start,qr,copy,button("Завершить сеанс","secondary",()->{if(peer!=null)peer.close();peer=null;code="";qr.setImage(null);status.setText("Обмен остановлен. Новый сеанс создаёт новый ключ и сертификат.");}));left.setPrefWidth(300);
  VBox right=column(label("Выберите музыку","section-title"),button("Выбрать все треки","text-button",()->{tracks.forEach(t->selected.add(t.id()));songs.refresh();}),songs,label("Плейлисты","subtitle"),lists,share,label("На телефоне можно отправить музыку в обратную сторону. Полученные данные добавляются только после просмотра и подтверждения.","muted"));right.setPrefWidth(440);
  getDialogPane().setContent(column(row(left,right),status));setOnHidden(e->{disposed=true;if(peer!=null)peer.close();});
 }
}
