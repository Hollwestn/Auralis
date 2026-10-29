package io.auralis.desktop;

import io.auralis.core.library.LibraryService;
import io.auralis.core.library.LibraryService.Reaction;
import io.auralis.core.model.*;
import io.auralis.core.recommendation.RecommendationService;
import io.auralis.desktop.playback.PlaybackService;
import io.auralis.desktop.ui.*;
import io.auralis.desktop.visualization.VisualizerPane;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.*;
import javafx.fxml.FXML;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import io.auralis.core.backup.LibraryArchive;
import static io.auralis.desktop.ui.Views.*;

public final class MainController implements AutoCloseable {
    @FXML private BorderPane root;
    @FXML private VBox sidebar,playlistNav,advancedNav,rightDock,dockQueue;
    @FXML private StackPane pageHost,coverHost,dockCover,workspaceStack;
    @FXML private Pane ambientHost;
    @FXML private ScrollPane rightDockScroll;
    @FXML private HBox volumeGroup;
    @FXML private Label breadcrumb,statusLabel,connectionLabel,trackTitle,trackArtist,timeLabel;
    @FXML private ProgressIndicator busy;
    @FXML private TextField searchField;
    @FXML private Slider seek,volume;
    @FXML private Button playButton,likeButton,shuffleButton,repeatButton,homeNav;
    @FXML private Button dockPlayButton,dockLikeButton;
    @FXML private Label dockTitle,dockArtist,dockQuality;
    private LibraryService library;
    private ExecutorService worker;
    private Stage stage;
    private PlaybackService playback;
    private List<Track> tracks=List.of();
    private List<Playlist> playlists=List.of();
    private Set<String> favorites=Set.of();
    private List<Track> recentlyPlayed=List.of();
    private List<RecommendationService.Recommendation> homeRecommendations=List.of();
    private Map<String,String> settings=new HashMap<>();
    private Runnable refreshPage=this::showHome;
    private boolean reduced,advanced,seeking;
    private boolean eqEnabled=true;
    private double playbackRate=1,playbackBalance;
    private Theme theme=Theme.OBSIDIAN_YELLOW;
    private final VisualizerPane visualizer=new VisualizerPane();
    private final AmbientBackdrop backdrop=new AmbientBackdrop();
    private VisualizerPane.Mode visualMode=VisualizerPane.Mode.BLACK_HOLE;
    private VisualizerPane.Quality visualQuality=VisualizerPane.Quality.MEDIUM;
    private boolean visualEnabled=true,backgroundEnabled=true,animationsEnabled=true,visualFeatured;
    private double visualIntensity=.9,visualIdleSpeed=1,visualPlayingSpeed=.75;
    private int visualFps=45;
    private String currentSection="Главная";
    private String activePlaylist;
    private double actualListened,lastTick;
    private Track previousTrack;
    private int pending;
    private volatile long queueSaveVersion;
    private volatile long audioConfigurationVersion;
    private volatile boolean loudnessWorking;
    private RecommendationService.WaveMode waveMode;
    private EditorView editor;
    private javafx.animation.Timeline saveTimer;
    private javafx.animation.Timeline sleepTicker;
    private final io.auralis.desktop.playback.SleepTimer sleepTimer = new io.auralis.desktop.playback.SleepTimer();
    private final SimpleStringProperty sleepLabel = new SimpleStringProperty("Таймер выключен");
    public void attach(LibraryService library,ExecutorService worker,Stage stage){
        this.library=library;this.worker=worker;this.stage=stage;this.playback=new PlaybackService(worker,library.dataDirectory().resolve("playback-cache"));
        Platform.getPreferences().reducedMotionProperty().addListener(systemMotionListener);
        for(String key:List.of("theme","reducedMotion","advanced","onboarded","genres","artists","lastTrack","lastPosition","serverUrl","language","volume"))settings.put(key,library.setting(key,key.equals("genres")||key.equals("artists")?library.setting("preferences."+key,""):""));
        try{theme=Theme.valueOf(settings.get("theme"));}catch(Exception ignored){}
        if(!library.setting("design.version","").equals("2")){setting("legacy.theme",theme.name());theme=Theme.OBSIDIAN_YELLOW;setting("theme",theme.name());setting("design.version","2");}
        loadVisualSettings();applyTheme(theme);
        ambientHost.getChildren().add(backdrop);backdrop.widthProperty().bind(ambientHost.widthProperty());backdrop.heightProperty().bind(ambientHost.heightProperty());
        root.addEventFilter(MouseEvent.MOUSE_MOVED,e->{var point=ambientHost.sceneToLocal(e.getSceneX(),e.getSceneY());backdrop.setPointer(point.getX()/Math.max(1,ambientHost.getWidth()),point.getY()/Math.max(1,ambientHost.getHeight()));});
        stage.iconifiedProperty().addListener((o,a,b)->updateVisualState());stage.widthProperty().addListener((o,a,b)->updateLayout());
        root.applyCss();root.lookupAll(".button").forEach(node->{if(node instanceof Button button&&button.getUserData() instanceof String icon)button.setGraphic(Icons.of(icon,button==playButton||button==dockPlayButton?20:18));});
        playback.setSpectrumListener((time,length,magnitudes,phases)->{visualizer.acceptSpectrum(magnitudes,time);backdrop.acceptSpectrum(magnitudes);});
        reduced=Boolean.parseBoolean(settings.get("reducedMotion"));advanced=Boolean.parseBoolean(settings.get("advanced"));updateAdvanced();
        playback.onError(this::error);playback.onFinished(t->{recordListen(false);if(waveMode!=null&&!playback.queue().isEmpty()&&playback.queue().getLast().id().equals(t.id()))refillWave();});
        playback.volumeProperty().bindBidirectional(volume.valueProperty());try{volume.setValue(Double.parseDouble(settings.get("volume")));}catch(Exception ignored){}
        eqEnabled=Boolean.parseBoolean(library.setting("eq.enabled","true"));playback.setEqEnabled(eqEnabled);
        for(int i=0;i<10;i++){settings.put("eq.saved."+i,library.setting("eq.saved."+i,"0"));try{playback.setGain(i,Double.parseDouble(library.setting("eq."+i,"0")));}catch(Exception ignored){}}
        playback.currentProperty().addListener((o,old,current)->{if(old!=null)recordListen(true);previousTrack=current;actualListened=0;lastTick=0;updateCurrent(current);});
        playback.playingProperty().addListener((o,a,b)->{playButton.setGraphic(Icons.of(b?"pause":"play",20));dockPlayButton.setGraphic(Icons.of(b?"pause":"play",20));playButton.setAccessibleText(b?"Пауза":"Слушать");dockPlayButton.setAccessibleText(b?"Пауза":"Слушать");visualizer.setPlaying(b);backdrop.setPlaying(b);});
        playback.positionProperty().addListener((o,a,b)->{if(playback.playingProperty().get()&&b.doubleValue()>=lastTick&&b.doubleValue()-lastTick<2)actualListened+=b.doubleValue()-lastTick;lastTick=b.doubleValue();if(!seeking)seek.setValue(b.doubleValue());updateTime();});
        playback.durationProperty().addListener((o,a,b)->{seek.setMax(Math.max(1,b.doubleValue()));updateTime();});
        seek.setOnMousePressed(e->seeking=true);seek.setOnMouseReleased(e->{playback.seek(seek.getValue());seeking=false;});seek.setOnKeyReleased(e->playback.seek(seek.getValue()));
        searchField.textProperty().addListener((o,a,b)->{if(b.isBlank()){refreshPage.run();return;}activePlaylist=null;showTrackPage("Поиск", "Результаты в вашей коллекции",tracks.stream().filter(t->(t.title()+" "+t.artist()+" "+t.album()+" "+t.genre()).toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))).toList());});
        root.setOnDragOver(e->{if(e.getDragboard().hasFiles()){e.acceptTransferModes(TransferMode.COPY);e.consume();}});root.setOnDragDropped(e->{if(e.getDragboard().hasFiles()){importPaths(e.getDragboard().getFiles().stream().map(java.io.File::toPath).toList());e.setDropCompleted(true);e.consume();}});
        saveTimer=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(15),e->savePosition()));saveTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);saveTimer.play();
        sleepTicker=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(.25),e->{
            if(sleepTimer.expired()){playback.pause();sleepTimer.cancel();playback.setSleepGain(1);sleepLabel.set("Таймер завершён");savePosition();}
            else if(sleepTimer.active()){playback.setSleepGain(sleepTimer.gain());sleepLabel.set("До паузы · "+clock(Math.ceil(sleepTimer.remainingMillis()/1000.0)));}
        }));sleepTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);sleepTicker.play();
        task("Восстановление обложек…",library::repairArtwork,()->reload(null));
        playback.onQueueChanged(()->{saveQueueState();updateDockQueue();});
        reload(()->{showHome();refreshAudioConfiguration();restoreQueueState();});
        updateLayout();updateVisualState();
    }
    private void updateTime(){timeLabel.setText(clock(playback.positionProperty().get())+" / "+clock(playback.durationProperty().get()));}
    private void loadVisualSettings(){backdrop.setBudget(visualizer::graphicsBudgetLevel);
        try{visualMode=VisualizerPane.parseMode(library.setting("visual.mode","BLACK_HOLE"));}catch(IllegalArgumentException ignored){}
        try{visualQuality=VisualizerPane.Quality.valueOf(library.setting("visual.quality","MEDIUM"));}catch(IllegalArgumentException ignored){}
        try{visualFps=Math.clamp(Integer.parseInt(library.setting("visual.fps","45")),15,60);}catch(NumberFormatException ignored){}
        try{visualIntensity=Math.clamp(Double.parseDouble(library.setting("visual.intensity","0.9")),0,2);}catch(NumberFormatException ignored){}
        visualIdleSpeed=readVisualSpeed("visual.idleSpeed",1);visualPlayingSpeed=readVisualSpeed("visual.playingSpeed",.75);
        visualEnabled=Boolean.parseBoolean(library.setting("visual.enabled","true"));backgroundEnabled=Boolean.parseBoolean(library.setting("background.enabled","true"));animationsEnabled=Boolean.parseBoolean(library.setting("animations.enabled","true"));
        visualizer.setAdaptive(Boolean.parseBoolean(library.setting("visual.adaptive","true")));visualizer.setMode(visualMode);visualizer.setQuality(visualQuality);visualizer.setIntensity(visualIntensity);visualizer.setFps(visualFps);visualizer.setEffectsEnabled(visualEnabled);visualizer.setAnimationsEnabled(animationsEnabled);visualizer.setUltraHd(Boolean.parseBoolean(library.setting("visual.ultrahd","false")));
    }
    private double readVisualSpeed(String key,double fallback){try{double value=Double.parseDouble(library.setting(key,Double.toString(fallback)));return Double.isFinite(value)?Math.clamp(value,0,2):fallback;}catch(NumberFormatException failure){return fallback;}}
    private void applyTheme(Theme selected){selected.apply(root);visualizer.setTheme(selected.accentColor(),selected.secondaryColor(),javafx.scene.paint.Color.web(selected.panel()));backdrop.setTheme(selected);if(editor!=null)editor.setTheme(selected);}
    private void updateVisualState(){
        boolean effectiveReduced=reduced||Platform.getPreferences().isReducedMotion();boolean active=!stage.isIconified();visualizer.setActive(active&&visualFeatured);visualizer.setEffectsEnabled(visualEnabled);visualizer.setAnimationsEnabled(animationsEnabled);visualizer.setReducedMotion(effectiveReduced);visualizer.setFps(visualFps);visualizer.setIntensity(visualIntensity);visualizer.setSpeeds(visualIdleSpeed,visualPlayingSpeed);
        backdrop.setQuality(visualQuality.ordinal());backdrop.setFps(visualFps);backdrop.setActive(active);backdrop.setEnabled(backgroundEnabled&&visualEnabled);backdrop.setReducedMotion(effectiveReduced||!animationsEnabled);backdrop.setIntensity(visualIntensity*.7);
    }
    private void updateLayout(){boolean tools=Set.of("Аудиостудия","Эквалайзер","Настройки","Сервер").contains(currentSection);boolean show=stage.getWidth()>=1320&&!tools;rightDockScroll.setVisible(show);rightDockScroll.setManaged(show);volumeGroup.setVisible(stage.getWidth()>=1130);volumeGroup.setManaged(stage.getWidth()>=1130);}
    private void selectVisualMode(VisualizerPane.Mode mode){visualMode=Objects.requireNonNull(mode);visualizer.setMode(mode);setting("visual.mode",mode.name());}
    public static String clock(double value){long s=(long)Math.max(0,value);return "%d:%02d".formatted(s/60,s%60);}
    public void task(String message,Runnable action,Runnable done){pending++;busy.setManaged(true);busy.setVisible(true);statusLabel.setText(message);worker.submit(()->{try{action.run();Platform.runLater(()->{finishTask();if(done!=null)done.run();});}catch(Throwable e){Platform.runLater(()->{finishTask();error(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());});}});}
    private void finishTask(){pending=Math.max(0,pending-1);busy.setManaged(pending>0);busy.setVisible(pending>0);if(pending==0)statusLabel.setText(tracks.size()+" треков · ваша музыка всегда рядом");}
    private record Catalog(List<Track> tracks,List<Playlist> playlists,Set<String> favorites,List<Track> recent,List<RecommendationService.Recommendation> recommendations){}
    private void reload(Runnable done){
        final Catalog[] result={null};
        task("Обновление медиатеки…",()->result[0]=new Catalog(library.tracks(),library.playlists(),library.favorites().stream().map(Track::id).collect(java.util.stream.Collectors.toSet()),library.history(150).stream().map(LibraryService.HistoryEntry::track).distinct().limit(6).toList(),new RecommendationService(library).recommend(RecommendationService.WaveMode.MY_WAVE,8)),()->{
            tracks=result[0].tracks;playlists=result[0].playlists;favorites=result[0].favorites;recentlyPlayed=result[0].recent;homeRecommendations=result[0].recommendations;
            if(playback.current()!=null)tracks.stream().filter(track->track.id().equals(playback.current().id())).findFirst().ifPresent(this::updateCurrent);
            if(pending==0)statusLabel.setText(tracks.size()+" треков · ваша музыка всегда рядом");
            playlistNav.getChildren().clear();for(Playlist playlist:playlists){Button b=button(playlist.name(),"nav-button",()->openPlaylist(playlist.id()));b.setGraphic(Icons.of("music",16));b.setMaxWidth(Double.MAX_VALUE);playlistNav.getChildren().add(b);}updateLike();updateDockQueue();if(done!=null)done.run();
        });
    }
    private void display(Node node,String section){currentSection=section;if(editor!=null&&!section.equals("Аудиостудия"))editor.pausePreview();visualFeatured=false;pageHost.getChildren().setAll(node);breadcrumb.setText(section);updateNavigation(sidebar,section);Motion.reveal(node,reduced||Platform.getPreferences().isReducedMotion()||!animationsEnabled);updateLayout();updateVisualState();}
    private void updateNavigation(Parent parent,String section){for(Node child:parent.getChildrenUnmodifiable()){if(child instanceof Button button&&button.getStyleClass().contains("nav-button")){button.getStyleClass().remove("active");if(button.getText().equals(section))button.getStyleClass().add("active");}if(child instanceof Parent nested)updateNavigation(nested,section);}}
    public void status(String message){statusLabel.setText(message);}
    public void error(String message){statusLabel.setText(message);Alert alert=new Alert(Alert.AlertType.ERROR);alert.initOwner(stage);styleDialog(alert);alert.setTitle("Auralis");alert.setHeaderText("Не удалось выполнить действие");alert.setContentText(message);alert.show();}
    private void styleDialog(Dialog<?> dialog){dialog.getDialogPane().getStylesheets().add(AuralisApp.class.getResource("auralis.css").toExternalForm());theme.apply(dialog.getDialogPane());}
    private final java.util.concurrent.ConcurrentMap<String,String> pendingSettings=new java.util.concurrent.ConcurrentHashMap<>();
    private void setting(String key,String value){settings.put(key,value);pendingSettings.put(key,value);worker.submit(()->{synchronized(library){if(!restoringLibrary&&value.equals(pendingSettings.get(key))){storePreference(key,value);pendingSettings.remove(key,value);}}});}
    private void storePreference(String key,String value){library.setSetting(key,value);if(key.equals("genres")||key.equals("artists"))library.setSetting("preferences."+key,value);}
    private void flushLibrarySnapshot(String queue){synchronized(library){pendingSettings.forEach((key,value)->{if(value.equals(pendingSettings.get(key))){storePreference(key,value);pendingSettings.remove(key,value);}});library.setSetting("playback.queue",queue);}}

    private Optional<String> ask(String title,String initial){TextInputDialog dialog=new TextInputDialog(initial);dialog.initOwner(stage);dialog.setTitle("Auralis");dialog.setHeaderText(title);dialog.setContentText("Название:");styleDialog(dialog);return dialog.showAndWait().map(String::strip).filter(s->!s.isBlank());}
    private boolean confirm(String title,String detail){Alert dialog=new Alert(Alert.AlertType.CONFIRMATION,detail,ButtonType.CANCEL,ButtonType.OK);dialog.initOwner(stage);dialog.setTitle("Auralis");dialog.setHeaderText(title);styleDialog(dialog);return dialog.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    @FXML public void showHome(){
        activePlaylist=null;refreshPage=this::showHome;
        var data=new DashboardView.Data(tracks,recentlyPlayed,homeRecommendations,playlists,favorites);
        var actions=new DashboardView.Actions(items->play(items,0),this::startWave,this::showWave,this::showLibrary,this::showFavorites,this::importFolder,this::createPlaylist,this::openPlaylist,this::selectVisualMode);
        display(scroll(DashboardView.build(data,actions,visualizer,playback.playingProperty(),visualMode,greeting())),"Главная");visualFeatured=true;updateVisualState();
    }
    private String greeting(){int hour=java.time.LocalTime.now().getHour();return hour<6?"Музыка для этой ночи":hour<12?"Доброе утро":hour<18?"Хорошего дня":"Добрый вечер";}
    private VBox stat(String value,String name){VBox card=card(label(value,"section-title"),label(name,"muted"));HBox.setHgrow(card,Priority.ALWAYS);card.setMaxWidth(Double.MAX_VALUE);return card;}
    private VBox emptyLibrary(){VBox empty=column(label("♪","empty-icon"),label("Ваша коллекция начинается здесь","section-title"),label("Добавьте папку или перетащите аудиофайлы в окно.\nМузыка останется на вашем компьютере.","subtitle"),row(button("Выбрать папку","primary",this::importFolder),button("Выбрать файлы","secondary",this::importMusic)));empty.getStyleClass().add("empty-state");return empty;}
    @FXML public void showLibrary(){activePlaylist=null;refreshPage=this::showLibrary;showTrackPage("Медиатека",tracks.size()+" треков · локальная коллекция",tracks);}
    @FXML public void showFavorites(){activePlaylist=null;refreshPage=this::showFavorites;showTrackPage("Любимые треки","Музыка, к которой хочется возвращаться",tracks.stream().filter(t->favorites.contains(t.id())).toList());}
    @FXML public void showHistory(){activePlaylist=null;refreshPage=this::showHistory;final List<Track>[] result=new List[]{List.of()};task("Загрузка истории…",()->result[0]=library.history(300).stream().map(LibraryService.HistoryEntry::track).distinct().toList(),()->showTrackPage("История","Последние прослушанные композиции",result[0]));}
    private void showTrackPage(String title,String subtitle,List<Track> items){
        Set<String> catalogIds=tracks.stream().map(Track::id).collect(java.util.stream.Collectors.toSet());
        items=items.stream().filter(track->catalogIds.contains(track.id())).toList();
        final List<Track> visibleItems=items;
        VBox page=page(title,subtitle);page.getChildren().add(libraryTabs());FlowPane actions=new FlowPane(10,10);
        Button start=button("Слушать","primary",()->play(visibleItems,0));start.setGraphic(Icons.of("play",16));Button shuffle=button("Перемешать","secondary",()->{playback.setShuffle(true);play(visibleItems,0);});shuffle.setGraphic(Icons.of("shuffle",16));
        actions.getChildren().addAll(start,shuffle,button("Добавить файлы","secondary",this::importMusic),button("Добавить папку","text-button",this::importFolder));page.getChildren().add(actions);
        TableView<Track> table=table(items);table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);actions.getChildren().add(button("Метаданные и обложки","secondary",()->editMetadata(new ArrayList<>(table.getSelectionModel().getSelectedItems()))));table.setPrefHeight(Math.max(350,stage.getHeight()-355));page.getChildren().add(table);
        Button remove=button("Удалить из медиатеки","secondary",()->removeTrack(table.getSelectionModel().getSelectedItem()));remove.setId("remove-library-track");remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());actions.getChildren().add(remove);
        table.setOnKeyPressed(event->{if(event.getCode()==KeyCode.DELETE&&table.getSelectionModel().getSelectedItem()!=null){removeTrack(table.getSelectionModel().getSelectedItem());event.consume();}});
        display(scroll(page),title);
    }
    private FlowPane libraryTabs(){FlowPane tabs=new FlowPane(8,8);tabs.getChildren().addAll(button("Треки","pill",this::showLibrary),button("Альбомы","pill",()->showGroups(false)),button("Исполнители","pill",()->showGroups(true)),button("Плейлисты","pill",this::showPlaylists),button("Недавно добавлено","pill",()->showSmart(LibraryService.SmartCollection.RECENT)),button("Открыть заново","pill",()->showSmart(LibraryService.SmartCollection.REDISCOVER)),button("Чаще слушаю","pill",()->showSmart(LibraryService.SmartCollection.MOST_PLAYED)));return tabs;}
    private void showSmart(LibraryService.SmartCollection collection){activePlaylist=null;refreshPage=()->showSmart(collection);String title=switch(collection){case RECENT->"Недавно добавлено";case REDISCOVER->"Открыть заново";case MOST_PLAYED->"Чаще слушаю";};final List<Track>[] result=new List[]{List.of()};task("Собираем подборку…",()->result[0]=library.smartCollection(collection),()->showTrackPage(title,collection==LibraryService.SmartCollection.RECENT?"Добавлено за последние 30 дней":collection==LibraryService.SmartCollection.REDISCOVER?"Не слушали последние 30 дней · пропуски не считаются прослушиванием":"По суммарному времени прослушивания · от 10 секунд",result[0]));}
    private VBox sleepControls(){Label remaining=new Label();remaining.getStyleClass().add("muted");remaining.textProperty().bind(sleepLabel);FlowPane choices=new FlowPane(8,8);for(int minutes:List.of(5,15,30,60))choices.getChildren().add(button(minutes+" мин","pill",()->{sleepTimer.start(minutes);playback.setSleepGain(1);sleepLabel.set("До паузы · "+minutes+":00");}));choices.getChildren().add(button("Выключить","text-button",()->{sleepTimer.cancel();playback.setSleepGain(1);sleepLabel.set("Таймер выключен");}));return card(label("Таймер сна","section-title"),remaining,choices,label("Последние 10 секунд звук плавно затихает. Громкость восстановится после паузы; таймер работает и в свёрнутом окне.","micro"));}
    private void showGroups(boolean artists){
        activePlaylist=null;refreshPage=()->showGroups(artists);String title=artists?"Исполнители":"Альбомы";Map<String,List<Track>> groups=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);for(Track track:tracks){String key=artists?track.artist():track.album()+" · "+track.artist();groups.computeIfAbsent(key,k->new ArrayList<>()).add(track);}
        FlowPane grid=new FlowPane(16,20);for(var group:groups.entrySet()){Track first=group.getValue().getFirst();Label name=label(group.getKey(),"tile-title");name.setMaxWidth(152);name.setWrapText(false);VBox card=new VBox(9,artwork(first,152),name,label(group.getValue().size()+" треков","tile-subtitle"));card.getStyleClass().add("track-card");card.setOnMouseClicked(e->{refreshPage=()->showTrackPage(group.getKey(),group.getValue().size()+" треков",group.getValue());refreshPage.run();});grid.getChildren().add(card);}
        if(groups.isEmpty())grid.getChildren().add(emptyLibrary());display(scroll(page(title,"Из вашей личной коллекции",libraryTabs(),grid)),title);
    }
    private void showPlaylists(){activePlaylist=null;refreshPage=this::showPlaylists;FlowPane grid=new FlowPane(14,14);for(Playlist playlist:playlists){VBox tile=card(label(playlist.name(),"section-title"),label(playlist.tracks().size()+" треков · "+clock(playlist.durationSeconds()),"muted"),button("Открыть плейлист  →","text-button",()->openPlaylist(playlist.id())));tile.setPrefWidth(244);grid.getChildren().add(tile);}grid.getChildren().add(button("+  Новый плейлист","primary",this::createPlaylist));display(scroll(page("Плейлисты","Собрано вами",libraryTabs(),grid)),"Плейлисты");}
    private TableView<Track> table(List<Track> items){TableView<Track> table=new TableView<>(FXCollections.observableArrayList(items));table.setFixedCellSize(62);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPlaceholder(label("Пока пусто. Добавьте музыку или измените поиск.","muted"));TableColumn<Track,String> index=new TableColumn<>("#");index.setMaxWidth(44);index.setCellValueFactory(c->new SimpleStringProperty(Integer.toString(items.indexOf(c.getValue())+1)));TableColumn<Track,String> title=new TableColumn<>("КОМПОЗИЦИЯ");title.setPrefWidth(270);title.setCellValueFactory(c->new SimpleStringProperty(c.getValue().title()));title.setCellFactory(c->new TableCell<>(){@Override protected void updateItem(String value,boolean empty){super.updateItem(value,empty);setText(null);if(empty||getIndex()<0||getIndex()>=getTableView().getItems().size()){setGraphic(null);return;}Track track=getTableView().getItems().get(getIndex());Label name=label(value,"track-title"),artist=label(track.artist(),"tile-subtitle");name.setWrapText(false);artist.setWrapText(false);VBox info=new VBox(5,name,artist);setGraphic(row(artwork(track,36),info));}});TableColumn<Track,String> album=new TableColumn<>("АЛЬБОМ");album.setPrefWidth(200);album.setCellValueFactory(c->new SimpleStringProperty(c.getValue().album()));TableColumn<Track,String> genre=new TableColumn<>("ЖАНР");genre.setPrefWidth(110);genre.setCellValueFactory(c->new SimpleStringProperty(c.getValue().genre()));TableColumn<Track,String> duration=new TableColumn<>("ВРЕМЯ");duration.setPrefWidth(75);duration.setMaxWidth(90);duration.setCellValueFactory(c->new SimpleStringProperty(c.getValue().formattedDuration()));TableColumn<Track,String> favorite=new TableColumn<>("♡");favorite.setMaxWidth(50);favorite.setCellValueFactory(c->new SimpleStringProperty(favorites.contains(c.getValue().id())?"♥":""));table.getColumns().addAll(index,title,album,genre,duration,favorite);
        table.setRowFactory(t->{TableRow<Track> r=new TableRow<>();r.setOnMouseClicked(e->{if(e.getClickCount()==2&&!r.isEmpty())play(new ArrayList<>(table.getItems()),r.getIndex());});r.setOnContextMenuRequested(e->{if(!r.isEmpty()){ContextMenu menu=context(r.getItem());menu.show(r,e.getScreenX(),e.getScreenY());}});r.setOnDragDetected(e->{if(activePlaylist!=null&&!r.isEmpty()){Dragboard db=r.startDragAndDrop(TransferMode.MOVE);ClipboardContent clip=new ClipboardContent();clip.putString(r.getItem().id());db.setContent(clip);e.consume();}});r.setOnDragOver(e->{if(activePlaylist!=null&&e.getDragboard().hasString())e.acceptTransferModes(TransferMode.MOVE);});r.setOnDragDropped(e->{if(activePlaylist!=null&&!r.isEmpty()&&e.getDragboard().hasString()){List<Track> ordered=new ArrayList<>(table.getItems());Track moving=ordered.stream().filter(x->x.id().equals(e.getDragboard().getString())).findFirst().orElse(null);if(moving!=null){ordered.remove(moving);ordered.add(Math.min(r.getIndex(),ordered.size()),moving);String id=activePlaylist;task("Сохранение порядка…",()->library.reorderPlaylist(id,ordered.stream().map(Track::id).toList()),()->openPlaylist(id));e.setDropCompleted(true);}}});return r;});return table;}
    private ContextMenu context(Track track){ContextMenu menu=new ContextMenu();menu.getItems().add(item("Метаданные и обложка",()->editMetadata(List.of(track))));menu.getItems().add(item("Записать теги в оригинал…",()->writeOriginalTags(track)));menu.getItems().add(item("Воспроизвести",()->play(List.of(track),0)));menu.getItems().add(item("Следующим",()->{playback.insertNext(track);status("Трек будет следующим");}));menu.getItems().add(item("В очередь",()->{playback.enqueue(track);status("Добавлено в очередь");}));menu.getItems().add(item(favorites.contains(track.id())?"Убрать из любимого":"В любимое",()->favorite(track)));Menu add=new Menu("В плейлист");for(Playlist p:playlists)add.getItems().add(item(p.name(),()->task("Добавление…",()->library.addToPlaylist(p.id(),track.id()),()->reload(null))));menu.getItems().add(add);if(activePlaylist!=null){String id=activePlaylist;menu.getItems().add(item("Удалить из плейлиста",()->task("Удаление…",()->library.removeFromPlaylist(id,track.id()),()->reload(()->openPlaylist(id)))));}
        menu.getItems().addAll(item("Больше такого",()->reaction(track,Reaction.MORE)),item("Меньше такого",()->reaction(track,Reaction.LESS)),item("Не нравится",()->reaction(track,Reaction.DISLIKE)),item("Не рекомендовать исполнителя",()->task("Сохранение…",()->library.excludeArtist(track.artist(),true),()->status("Исполнитель исключён из волны"))));
        if(advanced)menu.getItems().add(item("Открыть в аудиостудии",()->openEditor(track)));menu.getItems().add(item("Сведения об аудио",()->audioInfo(track)));menu.getItems().addAll(new SeparatorMenuItem(),item("Удалить из медиатеки",()->removeTrack(track)));return menu;}
    private void removeTrack(Track track){
        if(track==null||!confirm("Удалить трек из медиатеки?",track.artist()+" — "+track.title()+"\n\nТрек будет убран из локальной медиатеки, избранного, истории, плейлистов и очереди. Исходный аудиофайл останется на диске. При повторном сканировании папки его можно добавить снова."))return;
        task("Удаление из медиатеки…",()->library.removeTrack(track.id()),()->{
            playback.removeTrack(track.id());
            if(track.id().equals(settings.get("lastTrack"))){setting("lastTrack","");setting("lastPosition","0");}
            savePosition();updateDockQueue();
            reload(()->{refreshPage.run();status("Трек удалён из медиатеки · исходный файл сохранён");});
        });
    }
    private void editMetadata(List<Track> selected){if(selected.isEmpty()){status("Выберите один или несколько треков (Ctrl / Shift)");return;}new MetadataDialog(stage,theme,selected,library).showAndWait().ifPresent(edit->task("Сохранение метаданных…",()->{try{edit.apply(library);}catch(Exception e){throw new RuntimeException(e);}},()->reload(()->{refreshPage.run();status("Метаданные сохранены в библиотеке");})));}
    private void writeOriginalTags(Track track){if(!confirm("Записать теги в исходный файл?","Аудиодорожка не перекодируется. Рядом с исходником будет сохранена полная .bak-копия. Воспроизведение выбранного файла будет остановлено. Обратное действие — восстановить .bak."))return;if(playback.current()!=null&&playback.current().id().equals(track.id()))playback.setQueue(List.of(),0);task("Запись тегов с резервной копией…",()->{try{io.auralis.core.library.MetadataWriter.writeOriginal(library,track.id());}catch(Exception e){throw new RuntimeException(e);}},()->reload(()->{refreshPage.run();status("Теги записаны. Полная копия оригинала сохранена рядом с файлом");}));}
    private MenuItem item(String text,Runnable action){MenuItem item=new MenuItem(text);item.setOnAction(e->action.run());return item;}
    private void play(List<Track> items,int index){if(items.isEmpty()){status("Сначала добавьте музыку");return;}waveMode=null;playback.setQueue(items,index);}
    private void playWave(List<Track> items,RecommendationService.WaveMode mode){if(items.isEmpty()){status("Нет доступных треков для этого режима волны");return;}waveMode=mode;playback.setShuffle(false);playback.setQueue(items,0);}
    private void refillWave(){RecommendationService.WaveMode requested=waveMode;Set<String> recent=playback.queue().stream().skip(Math.max(0,playback.queue().size()-5)).map(Track::id).collect(java.util.stream.Collectors.toSet());worker.submit(()->{try{var recommender=new RecommendationService(library);var ranked=recommender.recommend(requested,30,recent);if(ranked.isEmpty())ranked=recommender.recommend(requested,30);List<Track> batch=ranked.stream().map(RecommendationService.Recommendation::track).toList();Platform.runLater(()->{if(waveMode==requested&&!batch.isEmpty())playWave(batch,requested);});}catch(Exception e){Platform.runLater(()->error(e.getMessage()));}});}
    @FXML public void togglePlay(){if(playback.current()==null){play(tracks,0);return;}playback.toggle();}
    @FXML public void previous(){playback.previous();}@FXML public void next(){playback.next();}public void seekRelative(double seconds){playback.seek(playback.positionProperty().get()+seconds);}
    @FXML private void toggleShuffle(){playback.setShuffle(!playback.shuffle());shuffleButton.getStyleClass().remove("active");if(playback.shuffle())shuffleButton.getStyleClass().add("active");saveQueueState();}
    @FXML private void toggleRepeat(){PlaybackService.Repeat mode=playback.cycleRepeat();repeatButton.setText(mode==PlaybackService.Repeat.ONE?"1":"");repeatButton.getStyleClass().remove("active");if(mode!=PlaybackService.Repeat.OFF)repeatButton.getStyleClass().add("active");status("Повтор: "+mode);saveQueueState();}
    @FXML private void toggleLike(){if(playback.current()!=null)favorite(playback.current());}
    private void favorite(Track track){task("Сохранение избранного…",()->library.toggleFavorite(track.id()),()->reload(()->{updateLike();refreshPage.run();}));}
    private void reaction(Track track,Reaction reaction){task("Настройка рекомендаций…",()->library.setReaction(track.id(),reaction),()->status("Учтём при следующем запуске волны"));}
    private void updateCurrent(Track track){
        if(track==null){trackTitle.setText("Ваша музыка ждёт");trackArtist.setText("Добавьте любимые композиции");coverHost.getChildren().setAll(label("a","cover-note"));dockTitle.setText("Включите свою музыку");dockArtist.setText("Выберите трек в коллекции");dockQuality.setText("Локальная музыка · без подписки");dockCover.getChildren().setAll(label("a","idle-cover-mark"));visualizer.setArtwork(null);updateLike();updateDockQueue();return;}trackTitle.setText(track.title());trackArtist.setText(track.artist());coverHost.getChildren().setAll(artwork(track,52));dockTitle.setText(track.title());dockArtist.setText(track.artist());dockCover.getChildren().setAll(artwork(track,224));
        String name=track.path().getFileName().toString();int dot=name.lastIndexOf('.');dockQuality.setText((dot<0?"АУДИО":name.substring(dot+1).toUpperCase(Locale.ROOT))+"  /  "+track.formattedDuration()+"  /  локальная копия");
        if(!track.artworkPath().isBlank()&&Files.isRegularFile(Path.of(track.artworkPath())))visualizer.setArtwork(new Image(Path.of(track.artworkPath()).toUri().toString(),512,512,true,true,true));else visualizer.setArtwork(null);
        updateLike();updateDockQueue();
    }
    private void updateLike(){boolean liked=playback!=null&&playback.current()!=null&&favorites.contains(playback.current().id());for(Button button:List.of(likeButton,dockLikeButton)){button.setText("");button.setGraphic(Icons.of("heart",18));button.getStyleClass().remove("active");if(liked)button.getStyleClass().add("active");}}
    private void updateDockQueue(){
        dockQueue.getChildren().clear();List<Track> queue=playback.queue();int position=playback.queueIndex();List<Track> upcoming=queue.stream().skip(Math.max(0,position+1)).limit(3).toList();
        for(int upcomingIndex=0;upcomingIndex<upcoming.size();upcomingIndex++){Track track=upcoming.get(upcomingIndex);int queueIndex=position+1+upcomingIndex;Label title=label(track.title(),"tile-title"),artist=label(track.artist(),"tile-subtitle");title.setWrapText(false);title.setMaxWidth(155);artist.setWrapText(false);artist.setMaxWidth(155);VBox info=new VBox(4,title,artist);HBox item=row(artwork(track,37),info);item.getStyleClass().add("queue-entry");item.setOnMouseClicked(e->play(queue,queueIndex));dockQueue.getChildren().add(item);}
        if(upcoming.isEmpty())dockQueue.getChildren().add(label(playback.current()==null?"Выберите трек в медиатеке":"Конец очереди. Добавьте ещё музыку или включите волну.","muted"));
    }
    private Node artwork(Track track,double size){Track catalog=tracks.stream().filter(item->item.id().equals(track.id())).findFirst().orElse(track);return DashboardView.artwork(catalog,size);}
    private void recordListen(boolean skipped){Track t=previousTrack;double listened=actualListened;actualListened=0;if(t!=null&&listened>1)worker.submit(()->{synchronized(library){if(library.track(t.id()).isPresent())library.recordListen(t.id(),listened,skipped);}});}
    private volatile boolean restoringLibrary;
    private void savePosition(){if(restoringLibrary)return;if(playback.current()!=null){setting("lastTrack",playback.current().id());setting("lastPosition",Double.toString(playback.positionProperty().get()));}setting("volume",Double.toString(volume.getValue()));saveQueueState();}
    private String queueStateJson(){
        var state=LibraryArchive.JSON.createObjectNode();var ids=state.putArray("queue");playback.queue().forEach(t->ids.add(t.id()));state.put("queueIndex",playback.queueIndex()).put("trackId",playback.current()==null?"":playback.current().id()).put("positionSeconds",playback.positionProperty().get()).put("repeat",playback.repeat().name()).put("shuffle",playback.shuffle()).put("playing",false);return state.toString();
    }
    private void saveQueueState(){if(restoringLibrary)return;String json=queueStateJson();long revision=++queueSaveVersion;worker.submit(()->{synchronized(library){if(!restoringLibrary&&queueSaveVersion==revision)library.setSetting("playback.queue",json);}});}

    private void restoreQueueState(){try{String json=library.setting("playback.queue","");if(!json.isBlank()){var state=LibraryArchive.JSON.readTree(json);List<Track> items=new ArrayList<>();int requested=state.path("queueIndex").asInt(),index=0;int originalIndex=0;for(var id:state.path("queue")){var track=library.track(id.asText());if(track.isPresent()){if(originalIndex<requested)index++;items.add(track.get());}originalIndex++;}if(!items.isEmpty()){playback.setRepeat(PlaybackService.Repeat.valueOf(state.path("repeat").asText("OFF")));playback.setShuffle(state.path("shuffle").asBoolean());playback.restoreQueue(items,Math.clamp(index,0,items.size()-1),state.path("positionSeconds").asDouble(),false);}return;}}catch(Exception ignored){}library.track(settings.get("lastTrack")).ifPresent(t->{try{playback.restore(t,Double.parseDouble(settings.get("lastPosition")));}catch(Exception ignored){}});}
    @FXML public void showQueue(){
        activePlaylist=null;refreshPage=this::showQueue;TableView<Track> table=table(playback.queue());table.setPrefHeight(Math.max(350,stage.getHeight()-390));
        table.setRowFactory(view->{TableRow<Track> row=new TableRow<>();row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty())playback.restoreQueue(playback.queue(),row.getIndex(),0,true);});
            row.setOnDragDetected(e->{if(row.isEmpty())return;Dragboard board=row.startDragAndDrop(TransferMode.MOVE);ClipboardContent content=new ClipboardContent();content.putString("auralis-queue:"+row.getIndex());board.setContent(content);e.consume();});
            row.setOnDragOver(e->{if(e.getDragboard().hasString()&&e.getDragboard().getString().startsWith("auralis-queue:"))e.acceptTransferModes(TransferMode.MOVE);e.consume();});
            row.setOnDragDropped(e->{try{String value=e.getDragboard().getString();if(value!=null&&value.startsWith("auralis-queue:")){int from=Integer.parseInt(value.substring(14)),to=row.isEmpty()?playback.queue().size()-1:row.getIndex();playback.moveQueueItem(from,to);e.setDropCompleted(true);showQueue();}}catch(Exception ignored){e.setDropCompleted(false);}e.consume();});return row;});
        Runnable remove=()->{int index=table.getSelectionModel().getSelectedIndex();if(index>=0){playback.removeQueueItem(index);showQueue();}};
        table.setOnKeyPressed(e->{int selected=table.getSelectionModel().getSelectedIndex();if(e.getCode()==KeyCode.DELETE){remove.run();e.consume();}else if(e.isAltDown()&&(e.getCode()==KeyCode.UP||e.getCode()==KeyCode.DOWN)&&selected>=0){int next=selected+(e.getCode()==KeyCode.UP?-1:1);if(next>=0&&next<playback.queue().size()){playback.moveQueueItem(selected,next);showQueue();}e.consume();}});
        Button undo=button("Отменить изменение","secondary",()->{playback.undoQueue();showQueue();});undo.setDisable(!playback.canUndoQueue());
        display(scroll(page("Очередь воспроизведения","Перетащите строку или используйте Alt + ↑ / ↓. Удаление — Delete. Текущий звук сохраняет позицию.",row(button("Удалить из очереди","secondary",remove),undo,button("Сохранить как плейлист","secondary",this::saveQueuePlaylist)),table)),"Очередь воспроизведения");
    }
    private void saveQueuePlaylist(){if(playback.queue().isEmpty())return;TextInputDialog dialog=new TextInputDialog("Моя очередь");dialog.initOwner(stage);dialog.setTitle("Сохранить очередь");dialog.setHeaderText("Название плейлиста");styleDialog(dialog);dialog.showAndWait().filter(s->!s.isBlank()).ifPresent(name->{List<Track> items=playback.queue();task("Сохранение очереди…",()->{Playlist playlist=library.createPlaylist(name);for(Track track:items)library.addToPlaylist(playlist.id(),track.id());},()->reload(()->{showQueue();status("Очередь сохранена в плейлист; одинаковые треки включены один раз");}));});}
    @FXML private void fullPlayer(){
        if(playback.current()==null){showHome();return;}Parent previous=visualizer.getParent();if(previous instanceof Pane pane)pane.getChildren().remove(visualizer);
        visualizer.setBackdropMode(false);StackPane stage=new StackPane(visualizer);stage.setPrefHeight(540);Label title=label(playback.current().title(),"hero-title"),artist=label(playback.current().artist(),"subtitle");
        title.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(()->playback.current()==null?"":playback.current().title(),playback.currentProperty()));artist.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(()->playback.current()==null?"":playback.current().artist(),playback.currentProperty()));
        Button prev=button("Предыдущий","secondary",this::previous),toggle=button("Играть / пауза","primary",this::togglePlay),next=button("Следующий","secondary",this::next);prev.setGraphic(Icons.of("previous",16));toggle.setGraphic(Icons.of("play",16));next.setGraphic(Icons.of("next",16));
        VBox page=page("Наедине с музыкой",DashboardView.modeName(visualMode),stage,title,artist,row(prev,toggle,next,button("Текст песни / LRC","secondary",()->{if(playback.current()!=null)lyrics(playback.current());})),sleepControls(),button("Настроить визуализацию","text-button",this::showSettings));
        display(scroll(page),"Плеер");visualFeatured=true;updateVisualState();
    }
    private void lyrics(Track track){Path lrc=track.path().resolveSibling(track.path().getFileName().toString().replaceFirst("\\.[^.]+$",".lrc"));Path txt=track.path().resolveSibling(track.path().getFileName().toString().replaceFirst("\\.[^.]+$",".txt"));Path file=Files.exists(lrc)?lrc:txt;if(!Files.exists(file)){status("Положите .lrc или .txt рядом с треком с таким же именем");return;}final String[] text={""};task("Загрузка текста…",()->{try{if(Files.size(file)>2_097_152)throw new IllegalArgumentException("Файл текста слишком большой");text[0]=Files.readString(file);}catch(Exception e){throw new RuntimeException(e);}},()->{Dialog<Void>d=new Dialog<>();d.initOwner(stage);d.setTitle(track.title());d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);if(file.equals(lrc)){var parsed=io.auralis.core.lyrics.LrcLyrics.parse(text[0]);ListView<String> lines=new ListView<>(FXCollections.observableArrayList(parsed.lines().stream().map(io.auralis.core.lyrics.LrcLyrics.Line::text).toList()));lines.setPrefSize(540,430);javafx.beans.value.ChangeListener<Number> listener=(o,a,b)->{if(playback.current()!=null&&playback.current().id().equals(track.id())){int index=parsed.indexAt(b.doubleValue());if(index>=0&&index!=lines.getSelectionModel().getSelectedIndex()){lines.getSelectionModel().select(index);lines.scrollTo(Math.max(0,index-3));}}};playback.positionProperty().addListener(listener);lines.setOnMouseClicked(e->{if(e.getClickCount()==2&&lines.getSelectionModel().getSelectedIndex()>=0&&playback.current()!=null&&playback.current().id().equals(track.id()))playback.seek(parsed.lines().get(lines.getSelectionModel().getSelectedIndex()).seconds());});d.setOnHidden(e->playback.positionProperty().removeListener(listener));d.getDialogPane().setContent(lines);}else{TextArea area=new TextArea(text[0]);area.setEditable(false);area.setWrapText(true);area.setPrefHeight(400);d.getDialogPane().setContent(area);}styleDialog(d);d.show();});}
    @FXML public void importMusic(){FileChooser chooser=new FileChooser();chooser.setTitle("Добавить музыку");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Аудиофайлы","*.mp3","*.flac","*.wav","*.m4a","*.ogg","*.aac","*.opus","*.aiff","*.wma"));List<java.io.File> files=chooser.showOpenMultipleDialog(stage);if(files!=null)importPaths(files.stream().map(java.io.File::toPath).toList());}
    public void importFolder(){DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("Папка с музыкой");java.io.File folder=chooser.showDialog(stage);if(folder!=null)importPaths(List.of(folder.toPath()));}
    private void importPaths(List<Path> paths){LibraryService.ImportResult[] result={null};task("Сканирование и проверка аудио…",()->result[0]=library.importPaths(paths),()->reload(()->{refreshPage.run();status("Добавлено: "+result[0].imported()+" · дубликатов: "+result[0].duplicates()+" · ошибок: "+result[0].failures().size());if(!result[0].failures().isEmpty())error(result[0].failures().stream().limit(8).map(f->f.path().getFileName()+": "+f.message()).collect(java.util.stream.Collectors.joining("\n")));}));}
    @FXML public void createPlaylist(){ask("Новый плейлист","").ifPresent(name->{Playlist[] result={null};task("Создание плейлиста…",()->result[0]=library.createPlaylist(name),()->reload(()->openPlaylist(result[0].id())));});}
    private void openPlaylist(String id){
        Playlist playlist=playlists.stream().filter(p->p.id().equals(id)).findFirst().orElse(null);
        if(playlist==null){reload(this::showLibrary);return;}
        activePlaylist=id;refreshPage=()->openPlaylist(id);
        String cover=playlist.artworkPath();if((cover==null||cover.isBlank())&&!playlist.tracks().isEmpty())cover=playlist.tracks().getFirst().artworkPath();
        VBox heading=column(label("СОБРАНО ВАМИ / ПЛЕЙЛИСТ","hero-kicker"),label(playlist.name(),"title"),label(playlist.tracks().size()+" треков · "+clock(playlist.durationSeconds()),"subtitle"));
        HBox banner=row(DashboardView.artwork(cover,128),heading);banner.getStyleClass().add("card");
        FlowPane actions=new FlowPane(10,10);Button start=button("Слушать","primary",()->play(playlist.tracks(),0));start.setGraphic(Icons.of("play",16));
        actions.getChildren().addAll(start,button("Переименовать","secondary",()->ask("Название плейлиста",playlist.name()).ifPresent(name->task("Сохранение…",()->library.renamePlaylist(id,name),()->reload(()->openPlaylist(id))))),button("Экспорт M3U8","secondary",()->exportPlaylist(id)),button("Обложка","secondary",()->playlistArtwork(id)),button("Удалить","text-button",()->{if(confirm("Удалить плейлист?","Файлы музыки останутся на месте."))task("Удаление…",()->library.deletePlaylist(id),()->reload(this::showLibrary));}));
        TableView<Track> table=table(playlist.tracks());table.setPrefHeight(Math.max(300,stage.getHeight()-430));
        VBox page=new VBox(20,banner,actions,label("Перетащите строки, чтобы изменить порядок","micro"),table);page.getStyleClass().add("page");display(scroll(page),"Плейлисты");
    }
    private void exportPlaylist(String id){FileChooser chooser=new FileChooser();chooser.setInitialFileName("playlist.m3u8");java.io.File file=chooser.showSaveDialog(stage);if(file!=null)task("Экспорт плейлиста…",()->{try{library.exportM3u(id,file.toPath());}catch(Exception e){throw new RuntimeException(e);}},()->status("Плейлист сохранён: "+file));}
    private void playlistArtwork(String id){FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Обложка","*.png","*.jpg","*.jpeg"));java.io.File file=chooser.showOpenDialog(stage);if(file!=null)task("Сохранение обложки…",()->{try{library.setPlaylistArtwork(id,file.toPath());}catch(Exception e){throw new RuntimeException(e);}},()->reload(()->openPlaylist(id)));}
    @FXML public void showWave(){
        activePlaylist=null;refreshPage=this::showWave;
        ComboBox<RecommendationService.WaveMode> modes=new ComboBox<>(FXCollections.observableArrayList(RecommendationService.WaveMode.values()));modes.setValue(waveMode==null?RecommendationService.WaveMode.MY_WAVE:waveMode);
        modes.setConverter(new javafx.util.StringConverter<>(){public String toString(RecommendationService.WaveMode value){return waveName(value);}public RecommendationService.WaveMode fromString(String value){return RecommendationService.WaveMode.MY_WAVE;}});
        FlowPane actions=new FlowPane(10,10);Button start=button("Начать слушать","primary",()->generateWave(modes.getValue().name(),true));start.setGraphic(Icons.of("play",16));actions.getChildren().addAll(modes,start,button("Показать подборку","secondary",()->generateWave(modes.getValue().name(),false)));
        VBox intro=column(label("ПЕРСОНАЛЬНЫЙ ЭФИР","hero-kicker"),label("На вашей\nчастоте.","hero-title"),label("Любимые треки и новые сочетания.\nПоток из вашей личной коллекции.","subtitle"));intro.setPrefWidth(325);intro.setMinWidth(280);
        StackPane hero=DashboardView.cinematic(visualizer,intro,label("МУЗЫКА НА ВАШЕЙ ЧАСТОТЕ","visual-status"));
        VBox page=page("Моя волна","Слушайте то, что близко именно вам.",hero,actions,card(label("С каждым прослушиванием — ближе к вам","section-title"),label("Подбор учитывает жанры, исполнителей, ваши реакции и историю. Причины выбора видны в подборке. Для режимов настроения используются указанные в файлах метаданные.","subtitle"),button("Мои предпочтения  →","text-button",this::showSettings)));
        display(scroll(page),"Моя волна");visualFeatured=true;updateVisualState();
    }
    private static String waveName(RecommendationService.WaveMode mode){if(mode==null)return "Моя волна";return switch(mode){case MY_WAVE->"Моя волна";case DISCOVERY->"Открытия";case FAVORITES->"Любимое";case FOCUS->"Сосредоточиться";case ENERGY->"Энергия";case NIGHT->"Ночь";case RELAX->"Расслабиться";case FAMILIAR->"Знакомое";};}
    private void startWave(){generateWave("MY_WAVE",true);}
    private void generateWave(String mode,boolean autoplay){if(tracks.isEmpty()){status("Добавьте музыку, чтобы запустить волну");return;}/* ranking is performed in the core service, never random shuffle */
        final List<RecommendationService.Recommendation>[] result=new List[]{List.of()};task("Подбираем музыку…",()->result[0]=new RecommendationService(library).recommend(RecommendationService.WaveMode.valueOf(mode),50),()->{List<Track> selected=result[0].stream().map(RecommendationService.Recommendation::track).toList();VBox page=page("Ваша волна",waveName(RecommendationService.WaveMode.valueOf(mode))+" · "+selected.size()+" композиций",row(button("▶  Слушать подборку","primary",()->playWave(selected,RecommendationService.WaveMode.valueOf(mode))),button("Изменить режим","secondary",this::showWave)));for(var rec:result[0].stream().limit(5).toList())page.getChildren().add(label(rec.track().title()+" — "+String.join(" · ",rec.reasons()),"muted"));TableView<Track> table=table(selected);table.setPrefHeight(420);page.getChildren().add(table);display(scroll(page),"Моя волна");if(autoplay)playWave(selected,RecommendationService.WaveMode.valueOf(mode));});}
    @FXML public void showEditor(){if(playback.current()!=null)openEditor(playback.current());else{display(scroll(page("Аудиостудия","Неразрушающее редактирование вашей музыки",card(label("Выберите композицию","section-title"),label("Запустите трек или выберите «Открыть в аудиостудии» в меню композиции.","subtitle"),button("Открыть медиатеку","primary",this::showLibrary)))),"Аудиостудия");}}
    private void openEditor(Track track){playback.pause();if(editor==null)editor=new EditorView(library,worker,this::error,this::status,stage);editor.setTheme(theme);display(scroll(editor.open(track)),"Аудиостудия");}
    private void audioInfo(Track track){task("Анализ аудио…",()->{try{var info=library.audio().probe(track.path());Platform.runLater(()->{Alert alert=new Alert(Alert.AlertType.INFORMATION);alert.initOwner(stage);alert.setHeaderText(track.title());alert.setContentText(info.codec().toUpperCase(Locale.ROOT)+" · "+info.sampleRate()+" Hz · "+info.channels()+" каналов\n"+(info.bitrate()/1000)+" kbps · "+clock(info.durationSeconds())+"\n"+track.path());styleDialog(alert);alert.show();});}catch(Exception e){throw new RuntimeException(e);}},null);}
    @FXML public void showEqualizer(){VBox page=page("Эквалайзер","Десять полос. Точное управление характером звука.");HBox bands=new HBox(18);bands.setAlignment(Pos.CENTER);int[] hz={32,64,125,250,500,1000,2000,4000,8000,16000};List<Slider> sliders=new ArrayList<>();for(int i=0;i<10;i++){final int band=i;Slider slider=new Slider(-12,12,playback.gain(i));slider.setOrientation(Orientation.VERTICAL);slider.setPrefHeight(220);Label value=label("%.1f dB".formatted(slider.getValue()),"muted");slider.valueProperty().addListener((o,a,b)->{playback.setGain(band,b.doubleValue());value.setText("%.1f dB".formatted(b.doubleValue()));});slider.valueChangingProperty().addListener((o,a,b)->{if(!b)setting("eq."+band,Double.toString(slider.getValue()));});slider.setOnMouseReleased(e->setting("eq."+band,Double.toString(slider.getValue())));sliders.add(slider);VBox col=column(value,slider,label(hz[i]<1000?Integer.toString(hz[i]):hz[i]/1000+"k","muted"));col.setAlignment(Pos.CENTER);bands.getChildren().add(col);}
        CheckBox enabled=new CheckBox("Эквалайзер включён");enabled.setSelected(eqEnabled);enabled.selectedProperty().addListener((o,a,b)->{eqEnabled=b;playback.setEqEnabled(b);setting("eq.enabled",b.toString());});ComboBox<String> presets=new ComboBox<>(FXCollections.observableArrayList("Нейтральный","Больше баса","Яркость","Вокал","Мой профиль"));presets.setValue("Нейтральный");presets.setOnAction(e->{double[] values=switch(presets.getValue()){case "Больше баса"->new double[]{6,5,3,1,0,0,0,0,0,0};case "Яркость"->new double[]{0,0,0,0,0,1,2,4,5,4};case "Вокал"->new double[]{-2,-2,-1,1,3,4,3,1,0,-1};case "Мой профиль"->{double[] saved=new double[10];for(int i=0;i<10;i++)try{saved[i]=Double.parseDouble(settings.getOrDefault("eq.saved."+i,"0"));}catch(Exception ignored){}yield saved;}default->new double[10];};for(int i=0;i<10;i++){sliders.get(i).setValue(values[i]);setting("eq."+i,Double.toString(values[i]));}});Slider balance=new Slider(-1,1,playbackBalance);balance.valueProperty().addListener((o,a,b)->{playbackBalance=b.doubleValue();playback.setBalance(playbackBalance);});Slider rate=new Slider(.5,2,playbackRate);Label speed=label("%.2f×".formatted(playbackRate),"muted");rate.valueProperty().addListener((o,a,b)->{playbackRate=b.doubleValue();playback.setRate(playbackRate);speed.setText("%.2f×".formatted(b.doubleValue()));});page.getChildren().addAll(row(enabled,spacer(),presets,button("Сохранить профиль","secondary",()->{for(int i=0;i<10;i++)setting("eq.saved."+i,Double.toString(sliders.get(i).getValue()));status("Профиль сохранён");})),card(bands),card(label("Баланс","section-title"),balance,label("Скорость воспроизведения","section-title"),row(rate,speed),label("Скорость JavaFX меняет также высоту тона. Независимые Time Stretch и Pitch Shift доступны при экспорте в аудиостудии.","muted")));display(scroll(page),"Эквалайзер");}
    @FXML public void showSettings(){
        activePlaylist=null;refreshPage=this::showSettings;
        FlowPane primaryThemes=new FlowPane(12,12),additionalThemes=new FlowPane(12,12);
        EnumMap<Theme,Button> themeButtons=new EnumMap<>(Theme.class);
        for(Theme value:Theme.values()){
            HBox colors=row(new javafx.scene.shape.Circle(7,value.accentColor()),new javafx.scene.shape.Circle(7,value.secondaryColor()),new javafx.scene.shape.Circle(7,javafx.scene.paint.Color.web(value.surface())));
            Label name=label(value.toString().replace(" · дополнительная",""),"tile-title");
            Label type=label(value==Theme.OBSIDIAN_YELLOW?"Основная тема":value.ordinal()<5?"Новая палитра":"Сохранённая палитра","micro");
            VBox preview=new VBox(12,colors,name,type);preview.setPrefSize(134,84);preview.setMinHeight(84);preview.setMaxHeight(84);
            Button pick=button("","theme-option",()->{
                theme=value;applyTheme(value);setting("theme",value.name());
                themeButtons.forEach((palette,button)->button.setStyle("-fx-background-color:"+palette.panel()+";-fx-border-color:"+(palette==theme?palette.accent():palette.surface())+";-fx-border-width:"+(palette==theme?"2":"1")+";-fx-padding:15;"));
                status("Тема "+value+" применена ко всему интерфейсу");
            });
            pick.setId("theme-"+value.name());pick.setGraphic(preview);pick.setMinSize(166,116);pick.setPrefSize(166,116);pick.setMaxSize(166,116);pick.setStyle("-fx-background-color:"+value.panel()+";-fx-border-color:"+(value==theme?value.accent():value.surface())+";-fx-border-width:"+(value==theme?"2":"1")+";-fx-padding:15;");
            themeButtons.put(value,pick);(value.ordinal()<5?primaryThemes:additionalThemes).getChildren().add(pick);
        }
        CheckBox motion=new CheckBox("Уменьшить движение и интенсивность эффектов");motion.setSelected(reduced);
        motion.selectedProperty().addListener((o,a,b)->{reduced=b;setting("reducedMotion",b.toString());updateVisualState();});
        CheckBox animations=new CheckBox("Анимации интерфейса и визуализаций");animations.setSelected(animationsEnabled);
        animations.selectedProperty().addListener((o,a,b)->{animationsEnabled=b;setting("animations.enabled",b.toString());updateVisualState();});
        CheckBox visual=new CheckBox("Музыкальные визуализации");visual.setSelected(visualEnabled);
        visual.selectedProperty().addListener((o,a,b)->{visualEnabled=b;setting("visual.enabled",b.toString());updateVisualState();});
        CheckBox background=new CheckBox("Интерактивный фон: частицы, связи и глубина");background.setSelected(backgroundEnabled);
        background.selectedProperty().addListener((o,a,b)->{backgroundEnabled=b;setting("background.enabled",b.toString());updateVisualState();});
        ComboBox<VisualizerPane.Mode> modes=new ComboBox<>(FXCollections.observableArrayList(VisualizerPane.Mode.values()));modes.setValue(visualMode);
        modes.setConverter(new javafx.util.StringConverter<>(){public String toString(VisualizerPane.Mode value){return DashboardView.modeName(value);}public VisualizerPane.Mode fromString(String value){return visualMode;}});
        modes.valueProperty().addListener((o,a,b)->selectVisualMode(b));
        ComboBox<VisualizerPane.Quality> quality=new ComboBox<>(FXCollections.observableArrayList(VisualizerPane.Quality.values()));quality.setValue(visualQuality);
        quality.valueProperty().addListener((o,a,b)->{visualQuality=b;visualizer.setQuality(b);backdrop.setQuality(b.ordinal());setting("visual.quality",b.name());});
        CheckBox autoQuality=new CheckBox("Автокачество по времени кадра (выключите для фиксированного качества)");autoQuality.setSelected(Boolean.parseBoolean(library.setting("visual.adaptive","true")));autoQuality.setOnAction(e->{library.setSetting("visual.adaptive",Boolean.toString(autoQuality.isSelected()));visualizer.setAdaptive(autoQuality.isSelected());});
        CheckBox ultraHd=new CheckBox("Black Hole · 4K Ultra HD · 3840 × 2160");ultraHd.setSelected(visualizer.isUltraHd());
        ultraHd.selectedProperty().addListener((o,a,b)->{visualizer.setUltraHd(b);setting("visual.ultrahd",b.toString());});
        ComboBox<Integer> fps=new ComboBox<>(FXCollections.observableArrayList(15,30,45,60));fps.setValue(visualFps);
        fps.valueProperty().addListener((o,a,b)->{visualFps=b;setting("visual.fps",b.toString());updateVisualState();});
        Slider intensity=new Slider(0,2,visualIntensity);intensity.setPrefWidth(260);Label amount=label(Math.round(visualIntensity*100)+"%","muted");
        intensity.valueProperty().addListener((o,a,b)->{visualIntensity=b.doubleValue();amount.setText(Math.round(visualIntensity*100)+"%");updateVisualState();});
        intensity.setOnMouseReleased(e->setting("visual.intensity",Double.toString(visualIntensity)));
        intensity.setOnKeyReleased(e->setting("visual.intensity",Double.toString(visualIntensity)));
        Slider idleSpeed=new Slider(0,2,visualIdleSpeed);idleSpeed.setId("visual-idle-speed");idleSpeed.setPrefWidth(260);idleSpeed.setBlockIncrement(.05);idleSpeed.setAccessibleText("Скорость без музыки");
        Label idleAmount=label(Math.round(visualIdleSpeed*100)+"%","muted");
        idleSpeed.valueProperty().addListener((o,a,b)->{visualIdleSpeed=b.doubleValue();idleAmount.setText(Math.round(visualIdleSpeed*100)+"%");updateVisualState();});
        idleSpeed.setOnMouseReleased(e->setting("visual.idleSpeed",Double.toString(visualIdleSpeed)));idleSpeed.setOnKeyReleased(e->setting("visual.idleSpeed",Double.toString(visualIdleSpeed)));
        Slider playingSpeed=new Slider(0,2,visualPlayingSpeed);playingSpeed.setId("visual-playing-speed");playingSpeed.setPrefWidth(260);playingSpeed.setBlockIncrement(.05);playingSpeed.setAccessibleText("Скорость при воспроизведении");
        Label playingAmount=label(Math.round(visualPlayingSpeed*100)+"%","muted");
        playingSpeed.valueProperty().addListener((o,a,b)->{visualPlayingSpeed=b.doubleValue();playingAmount.setText(Math.round(visualPlayingSpeed*100)+"%");updateVisualState();});
        playingSpeed.setOnMouseReleased(e->setting("visual.playingSpeed",Double.toString(visualPlayingSpeed)));playingSpeed.setOnKeyReleased(e->setting("visual.playingSpeed",Double.toString(visualPlayingSpeed)));
        var resetSpeed=button("Сбросить скорость","secondary",()->{idleSpeed.setValue(1);playingSpeed.setValue(.75);setting("visual.idleSpeed","1.0");setting("visual.playingSpeed","0.75");});
        CheckBox adv=new CheckBox("Улучшенный режим: эквалайзер и аудиостудия");adv.setSelected(advanced);
        adv.selectedProperty().addListener((o,a,b)->{advanced=b;updateAdvanced();setting("advanced",b.toString());});
        TextField genres=new TextField(settings.getOrDefault("genres",""));genres.setPromptText("Жанры через запятую");
        TextField artists=new TextField(settings.getOrDefault("artists",""));artists.setPromptText("Исполнители через запятую");
        FlowPane graphicsOptions=new FlowPane(12,12);graphicsOptions.getChildren().addAll(row(label("Режим","muted"),modes),row(label("Качество","muted"),quality),row(label("FPS","muted"),fps));
        VBox page=page("Настройки","Оформление, атмосфера и ваша музыка.",
            card(label("Выберите своё настроение","section-title"),primaryThemes,label("Дополнительные темы","eyebrow"),additionalThemes),
            card(label("Графика, которая слышит музыку","section-title"),graphicsOptions,autoQuality,ultraHd,row(label("Интенсивность","muted"),intensity,amount),row(label("Скорость без музыки","muted"),idleSpeed,idleAmount),row(label("Скорость при воспроизведении","muted"),playingSpeed,playingAmount),resetSpeed,visual,background,animations,motion,
                label("4K повышает разрешение Black Hole, сохраняя его размер в интерфейсе. Для плавности на слабом ПК отключите 4K. При сворачивании отрисовка останавливается.","micro"),
                button("Открыть визуализацию  →","text-button",this::showHome)),
            card(label("Инструменты для вашего звука","section-title"),adv),audioControls(),sleepControls(),
            card(label("Музыкальные предпочтения","section-title"),genres,artists,button("Сохранить предпочтения","secondary",()->{setting("genres",genres.getText());setting("artists",artists.getText());status("Предпочтения сохранены");})),
            card(label("Медиатека","section-title"),label("Оригиналы остаются на своих местах. Импорт, сканирование и анализ работают в фоне.","muted"),
                row(button("Добавить папку","secondary",this::importFolder),button("Пересканировать","secondary",()->task("Сканирование папок…",library::rescan,()->reload(this::showSettings))),button("Восстановить обложки","secondary",()->task("Поиск обложек в файлах и папках…",library::repairArtwork,()->reload(()->{if(playback.current()!=null)library.track(playback.current().id()).ifPresent(this::updateCurrent);showSettings();}))),button("Импорт M3U8","secondary",this::importPlaylist))),
            card(label("Один аккаунт на компьютере и телефоне","section-title"),label("Подключите собственный сервер, чтобы продолжать прослушивание на другом устройстве. Личная музыка отправляется только по вашему выбору.","muted"),button("Открыть серверную коллекцию","primary",this::showServer)),
            backupControls(),card(label("Мои устройства","section-title"),label("Прямой защищённый обмен музыкой по Wi-Fi без сервера.","muted"),button("Подключить телефон","secondary",()->new PeerDialog(stage,theme,library,tracks,playlists,worker,this::previewBackup).show())),
            card(label("Ваши данные","section-title"),label(library.dataDirectory().toString(),"micro"),button("Очистить историю","secondary",()->{if(confirm("Очистить историю?","Будет удалена только история прослушивания на этом компьютере."))task("Очистка…",library::clearHistory,()->status("История очищена"));}))
        );
        display(scroll(page),"Настройки");
    }
    private VBox backupControls(){return card(label("Резервная копия и перенос","section-title"),label("Избранное, плейлисты, история, обложки и настройки в одном файле. Аудио включается по выбору; пароли и серверные токены исключены.","muted"),row(button("Создать копию","secondary",this::exportBackup),button("Посмотреть и восстановить","secondary",this::importBackup)));}
    private void refreshAudioConfiguration(){
        long revision=++audioConfigurationVersion;boolean gapless=Boolean.parseBoolean(library.setting("audio.gapless","false")),normalize=Boolean.parseBoolean(library.setting("audio.normalization","false"));double fade=0,target=-16;try{fade=Math.clamp(Double.parseDouble(library.setting("audio.crossfadeSeconds","0")),0,12);target=Math.clamp(Double.parseDouble(library.setting("audio.targetLufs","-16")),-23,-12);}catch(NumberFormatException ignored){}final double seconds=fade,lufs=target;List<Track> catalog=List.copyOf(tracks);Set<String> disabled=disabledAlbums();
        worker.submit(()->{Map<String,Double> gains=new HashMap<>();if(normalize)for(Track track:catalog){var extra=library.extras(track.id());if(extra.loudnessLufs()!=null&&extra.truePeakDb()!=null)gains.put(track.id(),io.auralis.core.audio.LoudnessService.gainDb(extra.loudnessLufs(),extra.truePeakDb(),lufs));}Platform.runLater(()->{if(revision==audioConfigurationVersion)playback.configureContinuous(gapless||normalize||seconds>0,seconds,gains,disabled);});});
    }
    private Set<String> disabledAlbums(){try{var node=LibraryArchive.JSON.readTree(library.setting("audio.disabledAlbums","[]"));Set<String> values=new HashSet<>();node.forEach(n->values.add(n.asText()));return values;}catch(Exception e){return Set.of();}}
    private VBox audioControls(){
        CheckBox gapless=new CheckBox("Непрерывное воспроизведение без пауз · PCM");gapless.setSelected(Boolean.parseBoolean(library.setting("audio.gapless","false")));gapless.setOnAction(e->{library.setSetting("audio.gapless",Boolean.toString(gapless.isSelected()));refreshAudioConfiguration();});
        CheckBox normalize=new CheckBox("Выравнивать воспринимаемую громкость");normalize.setSelected(Boolean.parseBoolean(library.setting("audio.normalization","false")));normalize.setOnAction(e->{library.setSetting("audio.normalization",Boolean.toString(normalize.isSelected()));refreshAudioConfiguration();if(normalize.isSelected())analyzeLoudness();});
        double value=0;try{value=Double.parseDouble(library.setting("audio.crossfadeSeconds","0"));}catch(Exception ignored){}Slider fade=new Slider(0,12,Math.clamp(value,0,12));fade.setAccessibleText("Длительность перехода между треками в секундах");fade.setPrefWidth(240);fade.setBlockIncrement(1);Label amount=label(String.format(Locale.ROOT,"%.1f с",fade.getValue()),"muted");fade.valueProperty().addListener((o,a,b)->amount.setText(String.format(Locale.ROOT,"%.1f с",b.doubleValue())));Runnable save=()->{library.setSetting("audio.crossfadeSeconds",Double.toString(fade.getValue()));refreshAudioConfiguration();};fade.setOnMouseReleased(e->save.run());fade.setOnKeyReleased(e->save.run());
        ComboBox<Integer> target=new ComboBox<>(FXCollections.observableArrayList(-23,-18,-16,-14));try{target.setValue(Integer.parseInt(library.setting("audio.targetLufs","-16")));}catch(Exception ignored){target.setValue(-16);}target.setAccessibleText("Целевая громкость LUFS");target.setOnAction(e->{library.setSetting("audio.targetLufs",target.getValue().toString());refreshAudioConfiguration();});
        ComboBox<String> album=new ComboBox<>();Map<String,String> keys=new LinkedHashMap<>();tracks.stream().sorted(Comparator.comparing(Track::album)).forEach(t->keys.putIfAbsent(t.artist()+" — "+t.album(),io.auralis.desktop.playback.ContinuousPcmPlayer.albumKey(t)));album.getItems().setAll(keys.keySet());album.setPromptText("Альбом для исключения");album.setMaxWidth(340);CheckBox disable=new CheckBox("Отключить переходы для этого альбома");album.setOnAction(e->disable.setSelected(disabledAlbums().contains(keys.get(album.getValue()))));disable.setOnAction(e->{String key=keys.get(album.getValue());if(key==null)return;Set<String> values=new HashSet<>(disabledAlbums());if(disable.isSelected())values.add(key);else values.remove(key);library.setSetting("audio.disabledAlbums",LibraryArchive.JSON.valueToTree(values).toString());refreshAudioConfiguration();});
        return card(label("Переходы и громкость","section-title"),gapless,row(label("Переход между треками","muted"),fade,amount),normalize,row(label("Цель LUFS","muted"),target,button("Проанализировать файлы","secondary",this::analyzeLoudness)),album,disable,label("Исходные файлы не изменяются. Громкость измеряется по EBU R128 с true peak; усиление ограничено запасом 1 дБ. Непрерывный режим использует FFmpeg и один PCM-выход, включая эквалайзер. Первое открытие может потребовать подготовки кэша.","micro"));
    }
    private void analyzeLoudness(){if(loudnessWorking)return;loudnessWorking=true;List<Track> catalog=List.copyOf(tracks);int[] failed={0};task("Анализ громкости файлов…",()->{try{var analyzer=new io.auralis.core.audio.LoudnessService(library.audio());for(Track track:catalog){if(Thread.currentThread().isInterrupted())break;var previous=library.extras(track.id());if(previous.loudnessLufs()!=null)continue;try{var result=analyzer.analyze(track.path());library.setLoudness(track.id(),result.integratedLufs(),result.truePeakDb());}catch(Exception e){failed[0]++;}}}finally{loudnessWorking=false;}},()->{refreshAudioConfiguration();status("Анализ завершён"+(failed[0]>0?" · недоступных файлов: "+failed[0]:""));});}
    private void exportBackup(){
        Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle("Резервная копия Auralis");dialog.setHeaderText("Сохранить "+tracks.size()+" треков и личную библиотеку");CheckBox includeAudio=new CheckBox("Включить аудиофайлы");dialog.getDialogPane().setContent(column(includeAudio,label("Без аудио копия содержит метаданные и ссылки на прежние файлы. Сохраните архив вне каталога приложения.","muted")));dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,ButtonType.OK);styleDialog(dialog);if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
        FileChooser chooser=new FileChooser();chooser.setInitialFileName("Auralis-"+java.time.LocalDate.now()+".auralis");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Резервная копия Auralis","*.auralis"));var target=chooser.showSaveDialog(stage);if(target==null)return;savePosition();String queueSnapshot=queueStateJson();task("Создание резервной копии…",()->{try{flushLibrarySnapshot(queueSnapshot);LibraryArchive.write(library,target.toPath(),includeAudio.isSelected(),null);}catch(Exception e){throw new RuntimeException(e);}},()->status("Копия сохранена: "+target.getName()));
    }
    private void importBackup(){FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Резервная копия Auralis","*.auralis","*.zip"));var selected=chooser.showOpenDialog(stage);if(selected==null)return;previewBackup(selected.toPath());}
    private void previewBackup(Path archive){java.io.File selected=archive.toFile();LibraryArchive.Preview[] result={null};task("Чтение копии…",()->{try{result[0]=LibraryArchive.preview(selected.toPath(),library);}catch(Exception e){throw new RuntimeException(e);}},()->{
        var preview=result[0];Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle("Предварительный просмотр");dialog.setHeaderText(preview.tracks()+" треков · "+preview.playlists()+" плейлистов · "+preview.favorites()+" любимых");VBox content=column(label("История: "+preview.history()+" · аудио: "+String.format(Locale.ROOT,"%.1f",preview.audioBytes()/1048576.0)+" МБ · совпадений: "+preview.conflicts(),"muted"),label("Недоступные оригиналы без включённого аудио: "+preview.missingAudio()+". Их можно добавить позже; связи сохранятся.","muted"));int shown=0;for(var t:preview.manifest().path("tracks")){if(shown++==5)break;content.getChildren().add(label(t.path("artist").asText()+" — "+t.path("title").asText(),"muted"));}content.getChildren().add(label("При объединении совпавшие треки сохраняют текущие метаданные, а отличающиеся плейлисты добавляются отдельно.","muted"));dialog.getDialogPane().setContent(content);ButtonType merge=new ButtonType("Объединить",ButtonBar.ButtonData.OK_DONE),replace=new ButtonType("Заменить…",ButtonBar.ButtonData.OTHER);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,merge,replace);styleDialog(dialog);dialog.showAndWait().filter(type->type==merge||type==replace).ifPresent(type->{boolean replacement=type==replace;if(replacement&&!confirm("Заменить библиотеку?","Будут заменены плейлисты, история и настройки. Исходные аудиофайлы останутся. Предыдущее состояние будет сохранено в before-restore.auralis."))return;savePosition();String previousQueue=queueStateJson();restoringLibrary=true;++queueSaveVersion;playback.pause();task("Восстановление медиатеки…",()->{try{flushLibrarySnapshot(previousQueue);LibraryArchive.restore(library,selected.toPath(),replacement);}catch(Exception e){Platform.runLater(()->restoringLibrary=false);throw new RuntimeException(e);}},()->{playback.onQueueChanged(()->{});playback.setQueue(List.of(),0);playback.onQueueChanged(()->{saveQueueState();updateDockQueue();});applyRestoredPreferences();reload(()->{restoreQueueState();restoringLibrary=false;saveQueueState();showSettings();status("Резервная копия восстановлена");});});});
    });}
    private void applyRestoredPreferences(){pendingSettings.clear();
        settings.keySet().removeIf(LibraryArchive::portableSetting);settings.putAll(library.settingsSnapshot());
        try{theme=Theme.valueOf(library.setting("theme","OBSIDIAN_YELLOW"));}catch(Exception ignored){}
        reduced=Boolean.parseBoolean(library.setting("reducedMotion","false"));advanced=Boolean.parseBoolean(library.setting("advanced","false"));updateAdvanced();
        try{volume.setValue(Math.clamp(Double.parseDouble(library.setting("volume","0.75")),0,1));}catch(Exception ignored){}
        eqEnabled=Boolean.parseBoolean(library.setting("eq.enabled","true"));playback.setEqEnabled(eqEnabled);
        for(int i=0;i<10;i++)try{playback.setGain(i,Double.parseDouble(library.setting("eq."+i,"0")));}catch(Exception ignored){}
        loadVisualSettings();applyTheme(theme);refreshAudioConfiguration();updateVisualState();
    }
    private void importPlaylist(){FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Плейлист","*.m3u","*.m3u8"));java.io.File file=chooser.showOpenDialog(stage);if(file!=null)task("Импорт плейлиста…",()->{try{library.importM3u(file.toPath());}catch(Exception e){throw new RuntimeException(e);}},()->reload(this::showLibrary));}
    private void updateAdvanced(){advancedNav.setVisible(advanced);advancedNav.setManaged(advanced);}
    public void firstRun(){if(Boolean.parseBoolean(settings.get("onboarded")))return;Dialog<Void> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle("Добро пожаловать в Auralis");dialog.setHeaderText("Ваша музыка. Ваше пространство.");ComboBox<Theme> themes=new ComboBox<>(FXCollections.observableArrayList(Theme.values()));themes.setValue(theme);themes.setOnAction(e->{theme=themes.getValue();applyTheme(theme);setting("theme",theme.name());styleDialog(dialog);});TextField genres=new TextField();genres.setPromptText("Любимые жанры через запятую (необязательно)");TextField artists=new TextField();artists.setPromptText("Любимые исполнители (необязательно)");ComboBox<String> mode=new ComboBox<>(FXCollections.observableArrayList("Локальная музыка","Подключить сервер"));mode.setValue("Локальная музыка");dialog.getDialogPane().setContent(column(label("Язык интерфейса: русский","subtitle"),themes,mode,genres,artists,label("Добавить папку и настроить сервер можно сейчас или позже в настройках.","muted")));ButtonType folder=new ButtonType("Добавить папку",ButtonBar.ButtonData.OTHER);ButtonType start=new ButtonType("Начать",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(folder,start);styleDialog(dialog);Node folderButton=dialog.getDialogPane().lookupButton(folder);folderButton.addEventFilter(javafx.event.ActionEvent.ACTION,e->{importFolder();e.consume();});dialog.setOnHidden(e->{setting("genres",genres.getText());setting("artists",artists.getText());setting("onboarded","true");if(mode.getValue().equals("Подключить сервер"))showServer();});dialog.show();}
    private Map<String,Object> serverPlaybackState(){
        Map<String,Object> state=new HashMap<>();
        state.put("trackId",playback.current()==null?"":playback.current().id());state.put("positionSeconds",playback.positionProperty().get());state.put("playing",playback.playingProperty().get());
        state.put("queue",playback.queue().stream().map(Track::id).toList());state.put("queueIndex",playback.queueIndex());state.put("shuffle",playback.shuffle());state.put("repeat",playback.repeat().name());return state;
    }
    private void restoreServerState(com.fasterxml.jackson.databind.JsonNode state){
        applyRemoteSettings(state.path("settings"));if(state.path("localQueueIds").isEmpty()&&state.path("localTrackId").asText("").isBlank()){reload(()->status("Настройки восстановлены · на другом устройстве очередь пуста"));return;}final List<Track>[] restored=new List[]{List.of()};
        task("Восстановление очереди…",()->{
            List<Track> queue=new ArrayList<>();
            if(state.path("localQueueIds").isArray())for(var id:state.path("localQueueIds"))queue.add(library.track(id.asText()).orElseThrow(()->new IllegalArgumentException("Трек очереди не найден в локальной копии")));
            if(queue.isEmpty())queue.add(library.track(state.path("localTrackId").asText()).orElseThrow(()->new IllegalArgumentException("Трек не найден в локальной коллекции")));
            restored[0]=List.copyOf(queue);
        },()->reload(()->{
            int index=Math.clamp(state.path("queueIndex").asInt(0),0,restored[0].size()-1);
            playback.setShuffle(state.path("shuffle").asBoolean(false));
            try{playback.setRepeat(PlaybackService.Repeat.valueOf(state.path("repeat").asText("OFF")));}catch(IllegalArgumentException ignored){playback.setRepeat(PlaybackService.Repeat.OFF);}
            playback.restoreQueue(restored[0],index,state.path("positionSeconds").asDouble(0),state.path("playing").asBoolean(false));
            updateDockQueue();status("Очередь и позиция восстановлены");
        }));
    }
    private void applyRemoteSettings(com.fasterxml.jackson.databind.JsonNode remote){
        if(remote.hasNonNull("theme"))try{theme=Theme.valueOf(remote.path("theme").asText().trim().toUpperCase(Locale.ROOT).replace(' ','_'));setting("theme",theme.name());}catch(IllegalArgumentException ignored){}
        if(remote.has("advancedMode")){advanced=remote.path("advancedMode").asBoolean();setting("advanced",Boolean.toString(advanced));}
        if(remote.has("reducedMotion")){reduced=remote.path("reducedMotion").asBoolean();setting("reducedMotion",Boolean.toString(reduced));}
        if(remote.has("visualMode"))try{selectVisualMode(VisualizerPane.parseMode(remote.path("visualMode").asText()));}catch(IllegalArgumentException ignored){}
        if(remote.has("visualQuality"))try{visualQuality=VisualizerPane.Quality.valueOf(remote.path("visualQuality").asText());visualizer.setQuality(visualQuality);setting("visual.quality",visualQuality.name());}catch(IllegalArgumentException ignored){}
        if(remote.has("visualFps")){visualFps=Math.clamp(remote.path("visualFps").asInt(45),15,60);setting("visual.fps",Integer.toString(visualFps));}
        if(remote.has("visualIntensity")){double value=remote.path("visualIntensity").asDouble(.9);if(Double.isFinite(value)){visualIntensity=Math.clamp(value,0,2);setting("visual.intensity",Double.toString(visualIntensity));}}
        for(String key:List.of("visualIdleSpeed","visualPlayingSpeed")){var node=remote.path(key);if(node.isNumber()&&Double.isFinite(node.asDouble())&&node.asDouble()>=0&&node.asDouble()<=2){if(key.equals("visualIdleSpeed"))visualIdleSpeed=node.asDouble();else visualPlayingSpeed=node.asDouble();setting(key.equals("visualIdleSpeed")?"visual.idleSpeed":"visual.playingSpeed",Double.toString(node.asDouble()));}}
        if(remote.has("visualEnabled")){visualEnabled=remote.path("visualEnabled").asBoolean();setting("visual.enabled",Boolean.toString(visualEnabled));}
        if(remote.has("backgroundEnabled")){backgroundEnabled=remote.path("backgroundEnabled").asBoolean();setting("background.enabled",Boolean.toString(backgroundEnabled));}
        if(remote.has("animationsEnabled")){animationsEnabled=remote.path("animationsEnabled").asBoolean();setting("animations.enabled",Boolean.toString(animationsEnabled));}
        applyTheme(theme);updateAdvanced();updateVisualState();
    }
    private void showServer(){
        ServerView view=new ServerView(library,worker,stage,this::error,this::status,track->reload(()->play(List.of(track),0)),this::serverPlaybackState,this::restoreServerState);
        view.onLibraryChanged(()->{for(String key:List.of("genres","artists"))settings.put(key,library.setting(key,library.setting("preferences."+key,"")));reload(null);});
        display(scroll(view.build()),"Сервер");
    }
    public void smokeCheck(){for(Theme t:Theme.values()){applyTheme(t);showHome();snapshot("home-"+t.name().toLowerCase(Locale.ROOT));}applyTheme(theme);showLibrary();snapshot("library");showFavorites();snapshot("favorites");showWave();snapshot("wave");showSettings();snapshot("settings");advanced=true;updateAdvanced();showEqualizer();snapshot("equalizer");showHome();if(root.lookup(".player")==null||root.lookup(".sidebar")==null)throw new IllegalStateException("Shell missing");}
    private void snapshot(String name){try{root.applyCss();root.layout();root.setOpacity(1);for(Node node:pageHost.getChildren()){node.setOpacity(1);node.setTranslateY(0);}Path directory=library.dataDirectory().resolve("screenshots");Files.createDirectories(directory);javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(root.getScene().snapshot(null),null),"png",directory.resolve(name+".png").toFile());}catch(Exception e){throw new IllegalStateException("Screenshot failed: "+name,e);}}
    private final javafx.beans.value.ChangeListener<Boolean> systemMotionListener=(observable,old,value)->updateVisualState();
    @Override public void close(){Platform.getPreferences().reducedMotionProperty().removeListener(systemMotionListener);savePosition();recordListen(true);if(saveTimer!=null)saveTimer.stop();if(sleepTicker!=null)sleepTicker.stop();visualizer.close();backdrop.close();playback.close();if(editor!=null)editor.close();}
}

