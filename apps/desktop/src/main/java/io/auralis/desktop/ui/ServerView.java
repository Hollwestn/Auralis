package io.auralis.desktop.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.auralis.core.library.LibraryService;
import io.auralis.core.model.*;
import io.auralis.desktop.server.ServerClient;
import io.auralis.desktop.server.PlaybackSynchronization;
import io.auralis.desktop.server.HistorySynchronization;
import io.auralis.desktop.server.FavoriteSynchronization;
import io.auralis.desktop.server.SettingsSynchronization;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.*;
import java.nio.file.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static io.auralis.desktop.ui.Views.*;

/** Explicit server operations. Local originals are never uploaded by a scan or sync action. */
public final class ServerView {
    private final LibraryService library;
    private final ExecutorService worker;
    private final Consumer<String> error,status;
    private final Consumer<Track> play;
    private final Stage owner;
    private final Supplier<Map<String,Object>> playbackState;
    private final Consumer<JsonNode> resume;
    private final ObjectMapper json=new ObjectMapper();
    private ServerClient client;
    private VBox content;
    private JsonNode remoteTracks;
    private Set<String> favoriteIds=Set.of();
    private Map<String,String> explanations=Map.of();
    private TextField search;
    private Runnable libraryChanged=()->{};
    public ServerView(LibraryService library,ExecutorService worker,Stage owner,Consumer<String> error,Consumer<String> status,Consumer<Track> play,Supplier<Map<String,Object>> playbackState,Consumer<JsonNode> resume){this.library=library;this.worker=worker;this.owner=owner;this.error=error;this.status=status;this.play=play;this.playbackState=playbackState;this.resume=resume;}
    /** Notifies the shell on the JavaFX thread after server actions change local catalog state. */
    public void onLibraryChanged(Runnable callback){libraryChanged=Objects.requireNonNull(callback);}
    public VBox build(){
        TextField url=new TextField(library.setting("serverUrl","http://localhost:8080"));url.setPromptText("https://music.example.com");
        TextField email=new TextField();email.setPromptText("Email");PasswordField password=new PasswordField();password.setPromptText("Пароль (минимум 10 символов при регистрации)");
        TextField name=new TextField();name.setPromptText("Ваше имя (для регистрации)");CheckBox register=new CheckBox("Создать новый аккаунт");
        Button connect=button("Подключиться","primary",()->{connectAction(url.getText(),email.getText(),password.getText(),name.getText(),register.isSelected());password.clear();});
        content=column(card(label("Общий аккаунт для ПК и телефона","section-title"),url,email,password,name,register,connect,label("Пароль и токен остаются в памяти. Музыка отправляется только кнопкой «Загрузить файлы» после явного выбора.","muted")));
        return page("Серверная коллекция","Собственный каталог, плейлисты и продолжение прослушивания.",content);
    }
    private void background(String text,ThrowingRunnable work){status.accept(text);worker.submit(()->{try{work.run();}catch(InterruptedException e){Thread.currentThread().interrupt();Platform.runLater(()->error.accept("Операция прервана"));}catch(Exception e){Platform.runLater(()->error.accept(Objects.requireNonNullElse(e.getMessage(),"Ошибка сервера")));}});}
    @FunctionalInterface private interface ThrowingRunnable{void run()throws Exception;}
    private void connectAction(String url,String email,String password,String name,boolean register){background("Подключение к серверу…",()->{
        ServerClient connected=new ServerClient(url);connected.login(email,password,name,register);
        JsonNode list=connected.request("tracks?limit=1000","GET",null),favorites=connected.request("favorites","GET",null);library.setSetting("serverUrl",url);
        Set<String> ids=ids(favorites);Platform.runLater(()->{client=connected;remoteTracks=list;favoriteIds=ids;catalog();status.accept("Сервер подключён");});
    });}
    private FlowPane toolbar(){
        search=new TextField();search.setPromptText("Название, исполнитель или альбом");search.setPrefWidth(260);search.setOnAction(e->loadCatalog(search.getText()));
        ComboBox<String> wave=new ComboBox<>();wave.getItems().addAll("MY_WAVE","DISCOVERY","FAVORITES","FOCUS","ENERGY","NIGHT","RELAX","FAMILIAR");wave.setValue("MY_WAVE");
        FlowPane bar=new FlowPane(10,10);bar.getChildren().addAll(search,button("Найти","secondary",()->loadCatalog(search.getText())),button("Загрузить файлы","primary",this::chooseUpload),button("Плейлисты","secondary",this::showPlaylists),button("История","secondary",this::showHistory),wave,button("Запустить волну","secondary",()->loadWave(wave.getValue())),button("↑ Позиция","secondary",this::pushSync),button("↓ Продолжить","secondary",this::pullSync),button("↑ Коллекция","secondary",this::pushCollection),button("↓ Плейлисты и избранное","secondary",this::pullCollection));
        return bar;
    }
    private void catalog(){
        ListView<JsonNode> list=new ListView<>();for(JsonNode track:remoteTracks)list.getItems().add(track);list.setPrefHeight(580);
        list.setPlaceholder(label("Каталог пуст. Выберите «Загрузить файлы» и разрешённую музыку. Исходные файлы сохраняются.","subtitle"));
        list.setCellFactory(ignored->new ListCell<>(){
            @Override protected void updateItem(JsonNode track,boolean empty){super.updateItem(track,empty);setText(null);if(empty||track==null){setGraphic(null);return;}
                String id=track.path("id").asText();VBox info=column(label(track.path("title").asText(),"track-title"),label(track.path("artist").asText()+" · "+track.path("album").asText(),"muted"));
                if(explanations.containsKey(id))info.getChildren().add(label(explanations.get(id),"muted"));HBox.setHgrow(info,Priority.ALWAYS);
                setGraphic(row(info,button(favoriteIds.contains(id)?"♥":"♡","secondary",()->toggleFavorite(track)),button("+ Плейлист","secondary",()->choosePlaylist(track)),button("↓ Слушать","secondary",()->downloadAndPlay(track))));
            }
        });
        content.getChildren().setAll(toolbar(),label("↓ Слушать сохраняет личную офлайн-копию. ↑ Коллекция отправляет только уже связанные серверные треки; локальные файлы автоматически не загружаются.","muted"),list);
    }
    private void loadCatalog(String q){ServerClient c=client;background("Поиск на сервере…",()->{
        JsonNode tracks=c.request("tracks?limit=1000&q="+encode(q),"GET",null),favorites=c.request("favorites","GET",null);Set<String> ids=ids(favorites);
        Platform.runLater(()->{remoteTracks=tracks;favoriteIds=ids;explanations=Map.of();catalog();status.accept("Каталог обновлён");});
    });}
    private void chooseUpload(){
        FileChooser chooser=new FileChooser();chooser.setTitle("Явно загрузить выбранные файлы в свой серверный аккаунт");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Аудио (до 300 МБ на файл)","*.mp3","*.flac","*.wav","*.ogg","*.m4a","*.aiff","*.aif"));
        List<File> chosen=chooser.showOpenMultipleDialog(owner);if(chosen==null||chosen.isEmpty())return;ServerClient c=client;
        background("Загрузка выбранных файлов на сервер…",()->{
            int imported=0;List<String> failures=new ArrayList<>();
            for(File file:chosen){try{JsonNode remote=c.upload(file.toPath());for(Track local:library.tracks())if(Files.isRegularFile(local.path())&&Files.isSameFile(local.path(),file.toPath())){map(c,local.id(),remote.path("id").asText());break;}imported++;}catch(Exception e){failures.add(file.getName()+": "+e.getMessage());}}
            JsonNode tracks=c.request("tracks?limit=1000","GET",null);int count=imported;
            Platform.runLater(()->{remoteTracks=tracks;catalog();status.accept("Загружено файлов: "+count);if(!failures.isEmpty())error.accept(String.join("\n",failures));});
        });
    }
    private void downloadAndPlay(JsonNode metadata){ServerClient c=client;background("Загрузка личной офлайн-копии…",()->{Track local=cachedTrack(c,metadata);Platform.runLater(()->play.accept(local));});}
    private Track cachedTrack(ServerClient c,JsonNode metadata)throws Exception{
        String remoteId=UUID.fromString(metadata.path("id").asText()).toString();String mapped=localId(c,remoteId);
        Optional<Track> existing=library.track(mapped);if(existing.isPresent()&&Files.isRegularFile(existing.get().path()))return existing.get();
        String mime=metadata.path("mimeType").asText();String ext=mime.contains("mpeg")?".mp3":mime.contains("flac")?".flac":mime.contains("ogg")?".ogg":mime.contains("mp4")?".m4a":mime.contains("aiff")?".aiff":".wav";
        String cache=UUID.nameUUIDFromBytes(c.identity().getBytes(StandardCharsets.UTF_8)).toString();Path destination=library.dataDirectory().resolve("server-cache").resolve(cache).resolve(remoteId+ext);
        if(!Files.isRegularFile(destination))c.download(remoteId,destination);
        var result=library.importPaths(List.of(destination));if(!result.failures().isEmpty())throw new IOException(result.failures().getFirst().message());
        Track local=library.tracks().stream().filter(t->t.path().equals(destination.toAbsolutePath().normalize())).findFirst().orElse(null);
        if(local==null){long size=Files.size(destination);for(Track candidate:library.tracks())if(Files.isRegularFile(candidate.path())&&Files.size(candidate.path())==size&&Files.mismatch(destination,candidate.path())==-1){local=candidate;break;}}
        if(local==null)throw new IOException("Не удалось связать серверный трек с локальной библиотекой");
        if(local.path().startsWith(library.dataDirectory().resolve("server-cache")))library.updateMetadata(local.id(),metadata.path("title").asText(),metadata.path("artist").asText(),metadata.path("album").asText(),metadata.path("genre").asText());
        map(c,local.id(),remoteId);return library.track(local.id()).orElseThrow();
    }
    private Track cachedTrack(ServerClient c,String id)throws Exception{
        String local=localId(c,id);Optional<Track> existing=library.track(local);if(existing.isPresent()&&Files.isRegularFile(existing.get().path()))return existing.get();
        return cachedTrack(c,c.request("tracks/"+encode(id),"GET",null));
    }
    private void toggleFavorite(JsonNode track){ServerClient c=client;String id=track.path("id").asText();boolean selected=!favoriteIds.contains(id);background("Обновление избранного…",()->{
        c.request("favorites/"+encode(id),selected?"PUT":"DELETE",null);String local=localId(c,id);if(library.track(local).isPresent())FavoriteSynchronization.acknowledge(c,library,local,selected);
        JsonNode favorites=c.request("favorites","GET",null);Set<String> ids=ids(favorites);Platform.runLater(()->{favoriteIds=ids;libraryChanged.run();catalog();});
    });}
    private void loadWave(String mode){ServerClient c=client;background("Подбор музыки по вашему вкусу…",()->{
        JsonNode wave=c.request("wave?mode="+mode+"&limit=100","GET",null);ArrayNode tracks=json.createArrayNode();Map<String,String> reasons=new HashMap<>();
        for(JsonNode recommendation:wave){JsonNode t=recommendation.path("track");tracks.add(t);List<String> why=new ArrayList<>();recommendation.path("reasons").forEach(r->why.add(r.asText()));reasons.put(t.path("id").asText(),String.join(" · ",why));}
        Platform.runLater(()->{remoteTracks=tracks;explanations=reasons;catalog();status.accept("Волна: "+tracks.size()+" треков. Каждая рекомендация объяснена.");});
    });}
    private void choosePlaylist(JsonNode track){ServerClient c=client;background("Получение плейлистов…",()->{
        JsonNode playlists=c.request("playlists","GET",null);Platform.runLater(()->{
            List<Choice> choices=new ArrayList<>();playlists.forEach(p->choices.add(new Choice(p.path("id").asText(),p.path("name").asText(),p)));
            if(choices.isEmpty()){error.accept("Сначала создайте серверный плейлист в разделе «Плейлисты».");return;}
            ChoiceDialog<Choice> dialog=new ChoiceDialog<>(choices.getFirst(),choices);dialog.initOwner(owner);dialog.setTitle("Добавить в серверный плейлист");dialog.setHeaderText(track.path("title").asText());
            dialog.showAndWait().ifPresent(choice->background("Сохранение плейлиста…",()->{JsonNode fresh=c.request("playlists","GET",null);JsonNode current=null;for(JsonNode p:fresh)if(p.path("id").asText().equals(choice.id()))current=p;if(current==null)throw new IOException("Плейлист удалён на другом устройстве");List<String> ids=new ArrayList<>();current.path("trackIds").forEach(t->ids.add(t.asText()));ids.add(track.path("id").asText());Map<String,Object> update=new LinkedHashMap<>();update.put("name",current.path("name").asText());update.put("trackIds",ids);if(current.has("version"))update.put("version",current.path("version").asLong());c.request("playlists/"+encode(choice.id()),"PUT",update);Platform.runLater(()->status.accept("Трек добавлен в плейлист"));}));
        });
    });}
    private void showPlaylists(){ServerClient c=client;background("Загрузка серверных плейлистов…",()->{JsonNode playlists=c.request("playlists","GET",null);Platform.runLater(()->{
        VBox rows=column();for(JsonNode p:playlists){VBox info=column(label(p.path("name").asText(),"section-title"),label(p.path("trackIds").size()+" треков","muted"));HBox.setHgrow(info,Priority.ALWAYS);rows.getChildren().add(card(row(info,button("↓ В локальные","secondary",()->background("Получение музыки плейлиста…",()->{importPlaylist(c,p);Platform.runLater(()->{libraryChanged.run();status.accept("Плейлист и музыка сохранены на ПК");});})),button("Удалить","secondary",()->background("Удаление серверного плейлиста…",()->{c.request("playlists/"+encode(p.path("id").asText()),"DELETE",null);Platform.runLater(this::showPlaylists);})))));}
        if(playlists.isEmpty())rows.getChildren().add(label("Плейлистов пока нет. Создайте первый и добавляйте треки кнопкой + Плейлист.","subtitle"));
        content.getChildren().setAll(row(button("← Каталог","secondary",this::catalog),button("Создать плейлист","primary",()->{TextInputDialog dialog=new TextInputDialog();dialog.initOwner(owner);dialog.setTitle("Новый серверный плейлист");dialog.setHeaderText("Название плейлиста");dialog.showAndWait().filter(s->!s.isBlank()).ifPresent(name->background("Создание…",()->{c.request("playlists","POST",Map.of("name",name));Platform.runLater(this::showPlaylists);}));})),rows);
    });});}
    private void importPlaylist(ServerClient c,JsonNode remote)throws Exception{
        List<Track> tracks=new ArrayList<>();for(JsonNode id:remote.path("trackIds"))tracks.add(cachedTrack(c,id.asText()));
        String key=key(c,"serverPlaylist",remote.path("id").asText()),local=library.setting(key,"");Playlist p=library.playlist(local).orElse(null);
        if(p!=null){String baseline=library.setting(key(c,"playlistSnapshot",local),"");String current=playlistSnapshot(c,p);List<String> remoteIds=new ArrayList<>();remote.path("trackIds").forEach(id->remoteIds.add(id.asText()));String incoming=playlistSnapshot(remote.path("name").asText(),remoteIds);
            if(!current.equals(baseline)&&!current.equals(incoming)){
                // Keep unsent local edits before accepting the remote version.
                library.renamePlaylist(local,copyName(p.name(),"локальная копия"));library.setSetting(key(c,"localPlaylist",local),"");library.setSetting(key(c,"playlistVersion",local),"");library.setSetting(key(c,"playlistSnapshot",local),"");p=null;
            }
        }
        if(p==null){p=library.createPlaylist(remote.path("name").asText());local=p.id();library.setSetting(key,local);library.setSetting(key(c,"localPlaylist",local),remote.path("id").asText());}
        library.replacePlaylistContent(local,remote.path("name").asText(),tracks.stream().map(Track::id).toList());
        if(remote.has("version"))library.setSetting(key(c,"playlistVersion",local),Long.toString(remote.path("version").asLong()));
        library.setSetting(key(c,"playlistSnapshot",local),playlistSnapshot(c,library.playlist(local).orElseThrow()));
    }
    private void showHistory(){ServerClient c=client;background("Загрузка общей истории…",()->{JsonNode history=c.request("history?limit=200","GET",null),tracks=c.request("tracks?limit=1000","GET",null);Map<String,JsonNode> metadata=new HashMap<>();tracks.forEach(t->metadata.put(t.path("id").asText(),t));Platform.runLater(()->{
        VBox rows=column();for(JsonNode event:history){JsonNode t=metadata.get(event.path("trackId").asText());String title=t==null?"Удалённый трек":t.path("title").asText();rows.getChildren().add(card(label(title,"track-title"),label(event.path("playedAt").asText()+" · "+Math.round(event.path("playedSeconds").asDouble())+" сек.","muted")));}
        if(history.isEmpty())rows.getChildren().add(label("История появится после прослушивания и отправки коллекции.","subtitle"));
        content.getChildren().setAll(button("← Каталог","secondary",this::catalog),rows);
    });});}
    private void pushCollection(){ServerClient c=client;background("Передача коллекции связанных серверных треков…",()->{
        FavoriteSynchronization.Transfer favorites=FavoriteSynchronization.push(c,library);int playlists=0,skipped=0,copies=0;
        JsonNode remotePlaylists=c.request("playlists","GET",null);Set<String> existing=new HashSet<>();remotePlaylists.forEach(p->existing.add(p.path("id").asText()));
        for(Playlist p:library.playlists()){
            List<String> ids=new ArrayList<>();boolean missing=false;for(Track t:p.tracks()){String id=remoteId(c,t.id());if(id.isBlank()){missing=true;break;}ids.add(id);}if(missing){skipped++;continue;}
            String remote=library.setting(key(c,"localPlaylist",p.id()),""),baseline=library.setting(key(c,"playlistSnapshot",p.id()),""),expectedVersion=library.setting(key(c,"playlistVersion",p.id()),"");
            if(!remote.isBlank()&&existing.contains(remote)&&playlistSnapshot(c,p).equals(baseline))continue;
            boolean fork=!remote.isBlank()&&existing.contains(remote)&&expectedVersion.isBlank();String name=p.name();JsonNode saved;
            Map<String,Object> update=new LinkedHashMap<>();update.put("name",name);update.put("trackIds",ids);if(!expectedVersion.isBlank())update.put("version",Long.parseLong(expectedVersion));
            if(remote.isBlank()||!existing.contains(remote))update.remove("version");
            try{
                if(fork){name=copyName(name,"копия ПК");saved=c.request("playlists","POST",Map.of("name",name,"trackIds",ids));}
                else saved=c.request(remote.isBlank()||!existing.contains(remote)?"playlists":"playlists/"+encode(remote),remote.isBlank()||!existing.contains(remote)?"POST":"PUT",update);
            }catch(ServerClient.ApiFailure e){
                if(e.status()==404){skipped++;continue;}
                if(e.status()!=409)throw e;
                name=copyName(name,"копия ПК");saved=c.request("playlists","POST",Map.of("name",name,"trackIds",ids));fork=true;
            }
            if(fork){copies++;library.renamePlaylist(p.id(),name);library.setSetting(key(c,"serverPlaylist",remote),"");}
            library.setSetting(key(c,"localPlaylist",p.id()),saved.path("id").asText());library.setSetting(key(c,"serverPlaylist",saved.path("id").asText()),p.id());playlists++;
            if(saved.has("version"))library.setSetting(key(c,"playlistVersion",p.id()),Long.toString(saved.path("version").asLong()));
            library.setSetting(key(c,"playlistSnapshot",p.id()),playlistSnapshot(c,library.playlist(p.id()).orElseThrow()));
        }
        HistorySynchronization.Transfer history=HistorySynchronization.push(c,library);
        c.request("preferences","PUT",Map.of("genres",csv(library.setting("genres",library.setting("preferences.genres",""))),"artists",csv(library.setting("artists",library.setting("preferences.artists","")))));
        JsonNode remoteFavorites=c.request("favorites","GET",null);Set<String> ids=ids(remoteFavorites);String message="Отправлено изменений: избранное "+favorites.changed()+", плейлисты "+playlists+", история "+history.transferred()+". Конфликты сохранены отдельными копиями: "+copies+". Пропущено плейлистов: "+skipped+"; недоступных треков: "+(favorites.unavailable()+history.unavailable());
        Platform.runLater(()->{favoriteIds=ids;libraryChanged.run();catalog();status.accept(message);});
    });}
    private void pullCollection(){ServerClient c=client;background("Скачивание серверных плейлистов и избранного…",()->{
        JsonNode favorites=c.request("favorites","GET",null),playlists=c.request("playlists","GET",null),preferences=c.request("preferences","GET",null);
        Set<String> ids=ids(favorites);for(JsonNode t:favorites)cachedTrack(c,t);FavoriteSynchronization.pull(c,library,favorites);
        for(JsonNode p:playlists)importPlaylist(c,p);
        HistorySynchronization.Transfer history=HistorySynchronization.pull(c,library);
        for(String field:List.of("genres","artists")){List<String> values=new ArrayList<>();preferences.path(field).forEach(v->values.add(v.asText()));library.setSetting(field,String.join(", ",values));library.setSetting("preferences."+field,String.join(", ",values));}
        Platform.runLater(()->{favoriteIds=ids;libraryChanged.run();catalog();status.accept("Коллекция обновлена. Получено событий истории: "+history.transferred()+"; без локальной копии: "+history.unavailable()+". Аудио этих событий автоматически не скачивается.");});
    });}
    private void pushSync(){Map<String,Object> current=new HashMap<>(playbackState.get());ServerClient c=client;background("Сохранение позиции на сервере…",()->{
        Map<String,Object> playback=PlaybackSynchronization.toServer(current,id->remoteId(c,id),id->library.track(id).map(Track::title).orElse(id));playback.put("device","desktop");playback.put("source","SERVER");
        for(int attempt=0;attempt<2;attempt++){
            JsonNode state=c.request("sync","GET",null);Map<String,Object> settings=SettingsSynchronization.toServer(library,json.convertValue(state.path("settings"),new com.fasterxml.jackson.core.type.TypeReference<>(){}));
            Map<String,Object> merged=json.convertValue(state.path("playback"),new com.fasterxml.jackson.core.type.TypeReference<>(){});merged.putAll(playback);
            try{c.request("sync","PUT",Map.of("version",state.path("version").asLong(),"settings",settings,"playback",merged));break;}catch(ServerClient.ApiFailure e){if(e.status()==404)throw new IOException("Один из треков очереди удалён с сервера или недоступен аккаунту. Локальные копии сохранены; явно загрузите файл либо уберите его из очереди.",e);if(e.status()!=409||attempt==1)throw e;}
        }
        Platform.runLater(()->status.accept("Очередь, позиция и настройки сохранены. На другом устройстве нажмите «Продолжить»."));
    });}
    private void pullSync(){ServerClient c=client;background("Получение позиции с другого устройства…",()->{
        JsonNode state=c.request("sync","GET",null),position=state.path("playback");
        ObjectNode copy=PlaybackSynchronization.toLocal(position,id->cachedTrack(c,id).id());copy.set("settings",state.path("settings"));
        SettingsSynchronization.applyRemote(library,state.path("settings"));
        Platform.runLater(()->{resume.accept(copy);status.accept("Продолжение с позиции "+Math.round(position.path("positionSeconds").asDouble())+" сек.");});
    });}
    private String remoteId(ServerClient c,String id){return library.setting(key(c,"serverTrack",id),"");}
    private String localId(ServerClient c,String id){return library.setting(key(c,"localTrack",id),"");}
    private void map(ServerClient c,String local,String remote){library.setSetting(key(c,"serverTrack",local),remote);library.setSetting(key(c,"localTrack",remote),local);}
    private static String key(ServerClient c,String kind,String id){return kind+"."+c.identity()+"."+id;}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static Set<String> ids(JsonNode tracks){Set<String> ids=new HashSet<>();tracks.forEach(t->ids.add(t.path("id").asText()));return ids;}
    private static List<String> csv(String value){return Arrays.stream(value.split(",")).map(String::strip).filter(s->!s.isEmpty()).limit(100).toList();}
    private String playlistSnapshot(ServerClient c,Playlist playlist)throws Exception{return playlistSnapshot(playlist.name(),playlist.tracks().stream().map(t->{String id=remoteId(c,t.id());return id.isBlank()?"LOCAL:"+t.id():id;}).toList());}
    private String playlistSnapshot(String name,List<String> ids)throws Exception{Map<String,Object> value=new LinkedHashMap<>();value.put("name",name);value.put("trackIds",ids);return json.writeValueAsString(value);}
    private static String copyName(String name,String suffix){String tail=" ("+suffix+")";return name.substring(0,Math.min(name.length(),200-tail.length()))+tail;}
    private record Choice(String id,String name,JsonNode node){@Override public String toString(){return name+" · "+id.substring(0,Math.min(8,id.length()));}}
}
