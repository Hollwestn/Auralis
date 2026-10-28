package io.auralis.desktop.ui;

import io.auralis.core.library.LibraryService;
import io.auralis.core.model.Track;
import io.auralis.core.editor.*;
import io.auralis.desktop.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Standalone UI verification process. All generated catalog data is confined to a .runtime directory. */
public final class DesktopUiFlowHarness extends Application {
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private LibraryService library;
    private MainController controller;
    private Parent root;
    private Stage stage;
    private static volatile boolean failed;
    private final List<String> checks = new ArrayList<>();
    private final Deque<Runnable> steps = new ArrayDeque<>();
    private EditProject savedProject;
    private Track removedTrack;

    @Override public void init() throws Exception {
        Path data = Path.of(System.getProperty("auralis.data")).toAbsolutePath().normalize();
        if (!data.toString().contains(".runtime")) throw new IllegalArgumentException("UI harness requires an isolated .runtime data directory");
        library = new LibraryService(data);
        var imported = library.importPaths(List.of(Path.of(System.getProperty("auralis.fixture"))));
        if (!imported.failures().isEmpty() || library.tracks().isEmpty()) throw new IllegalStateException("UI fixtures did not import: " + imported);
        library.setSetting("advanced", "true"); library.setSetting("reducedMotion", "true"); library.setSetting("onboarded", "true"); library.setSetting("volume", "0");
        Track first = library.tracks().getFirst(); library.setFavorite(first.id(), true);
        if (library.playlists().isEmpty()) { var playlist = library.createPlaylist("QA • Local signals"); for (Track track : library.tracks()) library.addToPlaylist(playlist.id(), track.id()); }
    }
    @Override public void start(Stage stage) throws Exception {
        this.stage = stage; FXMLLoader loader = new FXMLLoader(AuralisApp.class.getResource("shell.fxml")); root = loader.load();
        Scene scene = new Scene(root, Double.parseDouble(System.getProperty("auralis.uiTestWidth","1320")), Double.parseDouble(System.getProperty("auralis.uiTestHeight","850"))); scene.getStylesheets().add(AuralisApp.class.getResource("auralis.css").toExternalForm());
        stage.setTitle("Auralis UI verification • isolated fixtures"); stage.setScene(scene); stage.show();
        controller = loader.getController(); controller.attach(library, worker, stage);
        steps.add(()->{
            try{
                var stateMethod=MainController.class.getDeclaredMethod("serverPlaybackState");stateMethod.setAccessible(true);
                Map<?,?> state=(Map<?,?>)stateMethod.invoke(controller);
                require(((Number)state.get("queueIndex")).intValue()==-1&&((List<?>)state.get("queue")).isEmpty(),"Empty queue must synchronize with index -1");
                var remote=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();remote.putObject("settings").put("theme","OBSIDIAN_YELLOW");remote.putArray("localQueueIds");
                var restore=MainController.class.getDeclaredMethod("restoreServerState",com.fasterxml.jackson.databind.JsonNode.class);restore.setAccessible(true);restore.invoke(controller,remote);
                checks.add("Empty queue sync index -1 and settings-only restore");
            }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        });
        steps.add(() -> capture("01-home"));
        steps.add(() -> { click("Медиатека"); });
        steps.add(() -> { assertTableCount(3); capture("02-library"); });
        steps.add(() -> { button("Недавно добавлено").fire(); });
        steps.add(() -> { assertTableCount(3); require(library.tracks().stream().allMatch(t->!t.artworkPath().isBlank()&&Files.isRegularFile(Path.of(t.artworkPath()))),"Cover cache missing for sidecar fixture"); capture("02a-smart-recent-covers"); checks.add("Smart recent collection and repaired cover cache"); click("Медиатека"); });
        steps.add(() -> { TextField search = (TextField) root.lookup("#searchField"); search.setText(library.tracks().getFirst().title()); });
        steps.add(() -> { assertTableCount(1); capture("03-search"); ((TextField) root.lookup("#searchField")).clear(); click("Любимые треки"); });
        steps.add(() -> { assertTableCount(1); capture("04-favorites"); click("Настройки"); });
        steps.add(() -> { button("5 мин").fire(); require(root.lookupAll(".label").stream().anyMatch(n->n instanceof Label l&&l.getText().startsWith("До паузы ·")),"Sleep countdown missing"); capture("04a-sleep-timer");button("Выключить").fire(); checks.add("Sleep timer starts, displays remaining time and cancels from settings"); });
        for(Theme palette:Theme.values()){
            steps.add(()->{theme(palette);require(root.getStyle().contains("-accent:"+palette.accent()),"Palette did not reach root: "+palette);capture("05-theme-"+palette.name().toLowerCase(Locale.ROOT));});
        }
        steps.add(()->{
            Slider idle=(Slider)root.lookup("#visual-idle-speed"),playing=(Slider)root.lookup("#visual-playing-speed");
            require(idle.getValue()==1&&playing.getValue()==.75,"Speed defaults must preserve idle and reduce playing by 25%");
            idle.setValue(1.4);playing.setValue(.5);
            for(Slider control:List.of(idle,playing))javafx.event.Event.fireEvent(control,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.RIGHT,false,false,false,false));
        });
        steps.add(()->await(()->library.setting("visual.idleSpeed","").equals("1.4")&&library.setting("visual.playingSpeed","").equals("0.5"),()->{click("Главная");click("Настройки");}));
        steps.add(()->{
            require(((Slider)root.lookup("#visual-idle-speed")).getValue()==1.4&&((Slider)root.lookup("#visual-playing-speed")).getValue()==.5,"Independent speeds were not restored");
            button("Сбросить скорость").fire();capture("06-visual-speed-settings");checks.add("Independent visual speeds persist and reset to 100% idle / 75% playing");
        });
        steps.add(()->ultraHdBox().fire());
        steps.add(()->await(()->Boolean.parseBoolean(library.setting("visual.ultrahd","false")),()->ultraHdBox().fire()));
        steps.add(()->await(()->!Boolean.parseBoolean(library.setting("visual.ultrahd","true")),()->{
            checks.add("4K toggle persists and restores adaptive resolution");theme(Theme.OBSIDIAN_YELLOW);click("Эквалайзер");
        }));
        steps.add(() -> { long sliders = root.lookupAll(".slider").stream().filter(n -> n instanceof Slider s && s.getOrientation() == javafx.geometry.Orientation.VERTICAL).count(); require(sliders == 10, "Expected 10 visible equalizer bands"); capture("08-equalizer"); stage.setWidth(1040); stage.setHeight(690); });
        steps.add(() -> { for (String selector : List.of("#playButton", "#shuffleButton", "#repeatButton", "#timeLabel")) { Node control = root.lookup(selector); var bounds = control.localToScene(control.getBoundsInLocal()); require(bounds.getMinX() >= 0 && bounds.getMaxX() <= root.getScene().getWidth() + 1 && bounds.getMinY() >= 0 && bounds.getMaxY() <= root.getScene().getHeight() + 1, "Player control clipped at minimum window: " + selector + " " + bounds); } capture("09-equalizer-minimum"); stage.setWidth(1320); stage.setHeight(850); click("Медиатека"); });
        steps.add(() -> { button("Слушать").fire(); });
        steps.add(() -> { require(!((Label)root.lookup("#trackTitle")).getText().contains("Найдите"), "Library Play action did not select a real track"); click("Аудиостудия"); });
        steps.add(() -> await(() -> root.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().startsWith("Предпрослушивание готово")), () -> {assertEditorWidth();capture("10-editor-rendered");}));
        steps.add(this::startPreview);
        steps.add(() -> await(() -> editorPreview() != null && editorPreview().getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING, () -> { capture("10b-editor-controls-preview-playing"); editorPlayButton().fire(); pageScroll().setVvalue(0); }));
        steps.add(() -> { javafx.scene.canvas.Canvas canvas = editorValue("canvas", javafx.scene.canvas.Canvas.class); require(canvas.getWidth() > 300, "Editor waveform canvas collapsed"); var fields = root.lookupAll(".text-field").stream().filter(n -> n instanceof TextField && n != root.lookup("#searchField")).map(n -> (TextField)n).sorted(Comparator.comparingDouble((TextField n) -> n.localToScene(n.getBoundsInLocal()).getMinY()).thenComparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX())).toList(); require(fields.size() >= 2, "Editor range controls absent"); fields.get(0).setText("1.00"); fields.get(1).setText("3.00"); button("Обрезать").fire(); });
        steps.add(() -> await(() -> !button("↶ Отменить").isDisabled() && root.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().startsWith("Предпрослушивание готово")), () -> { capture("11-editor-trimmed"); startPreview(); }));
        steps.add(() -> await(() -> editorPreview() != null && editorPreview().getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING, () -> { require(editorPreview().getTotalDuration().toSeconds() < 2.1, "Trim preview duration incorrect"); capture("11b-trimmed-preview-playing"); editorPlayButton().fire(); pageScroll().setVvalue(0); button("↶ Отменить").fire(); }));
        steps.add(() -> await(() -> !button("↷ Повторить").isDisabled() && root.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().startsWith("Предпрослушивание готово")), () -> { capture("12-editor-undone"); startPreview(); }));
        steps.add(() -> await(() -> editorPreview() != null && editorPreview().getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING, () -> { require(editorPreview().getTotalDuration().toSeconds() > 10, "Undo did not restore preview duration"); capture("12b-undone-preview-playing"); editorPlayButton().fire(); }));
        steps.add(() -> { editorValue("speed", Slider.class).setValue(1.25); editorValue("pitch", Slider.class).setValue(2); editorValue("fadeIn", TextField.class).setText("0.20"); editorValue("fadeOut", TextField.class).setText("0.20"); effectControls().get(EditProject.Effect.LIMITER).setSelected(true); button("Применить параметры").fire(); });
        steps.add(() -> await(this::editorRenderReady, () -> { assertEditorParameters(1.25, 2, .2, true); savedProject = editorValue("session", EditSession.class).project(); try { AudioEditor codec = new AudioEditor(library.audio()); Path file = library.dataDirectory().resolve("qa-processed.auralis.json"); codec.saveProject(savedProject, file); require(savedProject.equals(codec.loadProject(file)), "Processed project JSON round-trip changed parameters"); } catch (Exception error) { throw new IllegalStateException(error); } startPreview(); }));
        steps.add(() -> await(this::previewPlaying, () -> { require(Math.abs(editorPreview().getTotalDuration().toSeconds() - savedProject.durationSeconds()) < .15, "Processed preview duration incorrect"); capture("15-editor-parameters-applied"); editorPlayButton().fire(); pageScroll().setVvalue(0); button("↶ Отменить").fire(); }));
        steps.add(() -> await(this::editorRenderReady, () -> { assertEditorParameters(1, 0, 0, false); startPreview(); }));
        steps.add(() -> await(this::previewPlaying, () -> { require(editorPreview().getTotalDuration().toSeconds() > 15.8, "Undo parameter changes did not restore original duration"); capture("16-editor-parameters-undone"); editorPlayButton().fire(); restoreSavedProject(); }));
        steps.add(() -> await(this::editorRenderReady, () -> { assertEditorParameters(1.25, 2, .2, true); startPreview(); }));
        steps.add(() -> await(this::previewPlaying, () -> { capture("17-editor-project-loaded"); editorPlayButton().fire(); click("Моя волна"); }));
        steps.add(() -> button("Показать подборку").fire());
        steps.add(() -> await(() -> root.lookup(".table-view") instanceof TableView<?> table && !table.getItems().isEmpty(), () -> capture("13-wave-ranked")));
        steps.add(() -> { click("QA • Local signals"); });
        steps.add(() -> { assertTableCount(3); capture("14-playlist"); click("Медиатека"); });
        steps.add(() -> { TableView<Track> table=trackTable();require(button("Удалить из медиатеки").isDisabled(),"Removal without selection must be disabled");table.getSelectionModel().selectFirst();removedTrack=table.getSelectionModel().getSelectedItem();respondToRemoval(false);button("Удалить из медиатеки").fire(); });
        steps.add(() -> { assertTableCount(3);require(library.track(removedTrack.id()).isPresent(),"Cancel deleted a catalog entry");require(Files.isRegularFile(removedTrack.path()),"Cancel removed original audio");checks.add("Library removal cancellation preserves catalog and original");requestContextRemoval(); });
        steps.add(() -> await(()->library.track(removedTrack.id()).isEmpty()&&trackTable().getItems().size()==2,()->{
            require(Files.isRegularFile(removedTrack.path()),"Library deletion removed original audio");require(library.playlists().stream().allMatch(p->p.tracks().stream().noneMatch(t->t.id().equals(removedTrack.id()))),"Deleted track remains in a playlist");require(library.favorites().stream().noneMatch(t->t.id().equals(removedTrack.id())),"Deleted favorite remains");capture("18-track-removed");checks.add("Context menu deletes catalog entry and playlist/favorite references, preserving audio");
            trackTable().getSelectionModel().selectFirst();removedTrack=trackTable().getSelectionModel().getSelectedItem();respondToRemoval(true);javafx.event.Event.fireEvent(trackTable(),new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.DELETE,false,false,false,false));
        }));
        steps.add(() -> await(()->library.track(removedTrack.id()).isEmpty()&&trackTable().getItems().size()==1,()->{require(Files.isRegularFile(removedTrack.path()),"Delete key removed original audio");checks.add("Delete key confirms and removes only the selected track");finish();}));
        if(getParameters().getRaw().contains("--removal-only")){List<Runnable> removalSteps=new ArrayList<>(steps).subList(steps.size()-4,steps.size());steps.clear();steps.add(controller::showLibrary);steps.addAll(removalSteps);}
        after(1800, this::next);
    }
    private void theme(Theme theme) {
        Button pick=(Button)root.lookup("#theme-"+theme.name());require(pick!=null,"Theme control missing: "+theme);pick.fire();
    }
    @SuppressWarnings("unchecked") private TableView<Track> trackTable(){return (TableView<Track>)root.lookup(".table-view");}
    private void respondToRemoval(boolean accept){after(120,()->{try{
        DialogPane pane=javafx.stage.Window.getWindows().stream().filter(w->w.isShowing()&&w.getScene()!=null&&w.getScene().getRoot() instanceof DialogPane).map(w->(DialogPane)w.getScene().getRoot()).findFirst().orElseThrow(()->new IllegalStateException("Removal confirmation missing"));
        require(pane.getContentText().contains("Исходный аудиофайл останется на диске"),"Removal confirmation must explain original-file preservation");((Button)pane.lookupButton(accept?ButtonType.OK:ButtonType.CANCEL)).fire();
    }catch(Throwable error){abort(error);}});}
    private void requestContextRemoval(){
        root.applyCss();root.layout();TableRow<?> row=trackTable().lookupAll(".table-row-cell").stream().filter(n->n instanceof TableRow<?> r&&r.getItem() instanceof Track t&&t.id().equals(removedTrack.id())).map(n->(TableRow<?>)n).findFirst().orElseThrow();
        row.getOnContextMenuRequested().handle(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,0,0,stage.getX()+400,stage.getY()+300,false,new PickResult(row,0,0)));
        ContextMenu menu=javafx.stage.Window.getWindows().stream().filter(w->w.isShowing()&&w instanceof ContextMenu).map(w->(ContextMenu)w).findFirst().orElseThrow();MenuItem remove=menu.getItems().stream().filter(item->"Удалить из медиатеки".equals(item.getText())).findFirst().orElseThrow();menu.hide();respondToRemoval(true);remove.fire();
    }
    private CheckBox ultraHdBox(){return root.lookupAll(".check-box").stream().filter(n->n instanceof CheckBox c&&c.getText().startsWith("Black Hole · 4K")).map(n->(CheckBox)n).findFirst().orElseThrow();}
    private void click(String contains) { Button found = root.lookupAll(".sidebar .button").stream().filter(n -> n instanceof Button b && b.getText().contains(contains)).map(n -> (Button)n).findFirst().orElseThrow(() -> new IllegalStateException("Navigation absent: " + contains)); found.requestFocus(); found.fire(); require(found.getStyleClass().contains("active")||contains.equals("QA • Local signals"),"Active navigation state missing: "+contains); checks.add("Navigation: " + contains); }
    private javafx.scene.media.MediaPlayer editorPreview() {
        try { var editorField = MainController.class.getDeclaredField("editor"); editorField.setAccessible(true); Object editor = editorField.get(controller); var previewField = EditorView.class.getDeclaredField("preview"); previewField.setAccessible(true); return (javafx.scene.media.MediaPlayer)previewField.get(editor); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect editor native preview in test harness", e); }
    }
    private Button editorPlayButton() {
        try { var editorField = MainController.class.getDeclaredField("editor"); editorField.setAccessible(true); Object editor = editorField.get(controller); var playField = EditorView.class.getDeclaredField("play"); playField.setAccessible(true); return (Button)playField.get(editor); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private <T> T editorValue(String name, Class<T> type) {
        try { var editorField = MainController.class.getDeclaredField("editor"); editorField.setAccessible(true); Object editor = editorField.get(controller); var field = EditorView.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(editor)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked") private Map<EditProject.Effect, CheckBox> effectControls() { return editorValue("effects", Map.class); }
    private void assertEditorParameters(double speed, double pitch, double fade, boolean limiter) {
        EditProject project = editorValue("session", EditSession.class).project();
        require(Math.abs(editorValue("speed", Slider.class).getValue() - speed) < .001 && Math.abs(project.speed() - speed) < .001, "Speed widget/project are out of sync");
        require(Math.abs(editorValue("pitch", Slider.class).getValue() - pitch) < .001 && Math.abs(project.pitchSemitones() - pitch) < .001, "Pitch widget/project are out of sync");
        require(Math.abs(Double.parseDouble(editorValue("fadeIn", TextField.class).getText()) - fade) < .001 && Math.abs(project.fadeInSeconds() - fade) < .001, "Fade In widget/project are out of sync");
        require(Math.abs(Double.parseDouble(editorValue("fadeOut", TextField.class).getText()) - fade) < .001 && Math.abs(project.fadeOutSeconds() - fade) < .001, "Fade Out widget/project are out of sync");
        require(effectControls().get(EditProject.Effect.LIMITER).isSelected() == limiter && project.effects().contains(EditProject.Effect.LIMITER) == limiter, "Effect widget/project are out of sync");
        checks.add("Editor widget/project parameters: " + speed + ", " + pitch + ", " + fade + ", limiter=" + limiter);
    }
    private boolean editorRenderReady() { return !editorPlayButton().isDisabled() && root.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().startsWith("Предпрослушивание готово")); }
    private boolean previewPlaying() { return editorPreview() != null && editorPreview().getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING; }
    private void restoreSavedProject() {
        try {
            AudioEditor codec = new AudioEditor(library.audio()); EditProject loaded = codec.loadProject(library.dataDirectory().resolve("qa-processed.auralis.json"));
            var editorField = MainController.class.getDeclaredField("editor"); editorField.setAccessible(true); Object editor = editorField.get(controller);
            var sessionField = EditorView.class.getDeclaredField("session"); sessionField.setAccessible(true); sessionField.set(editor, new EditSession(loaded));
            var changedMethod = EditorView.class.getDeclaredMethod("changed"); changedMethod.setAccessible(true); changedMethod.invoke(editor);
            checks.add("Loaded actual saved project and refreshed editor controls");
        } catch (ReflectiveOperationException | java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void assertEditorWidth(){root.applyCss();root.layout();for(Node node:((Parent)root.lookup("#pageHost")).lookupAll(".button")){var bounds=node.localToScene(node.getBoundsInLocal());require(bounds.getMaxX()<=root.getScene().getWidth()+1,"Editor control clipped horizontally: "+node+" "+bounds);}checks.add("Editor controls fit available width");}
    private void startPreview() {
        pageScroll().setVvalue(1); editorPlayButton().fire();
        if (editorPreview() == null) { String dialogs = javafx.stage.Window.getWindows().stream().filter(w -> w.isShowing() && w != stage).flatMap(w -> w.getScene().getRoot().lookupAll(".label").stream()).filter(n -> n instanceof Label).map(n -> ((Label)n).getText()).collect(java.util.stream.Collectors.joining(" | ")); throw new IllegalStateException("Editor native preview failed: " + dialogs); }
        editorPreview().setVolume(0);
    }
    private ScrollPane pageScroll() { return (ScrollPane)((Parent)root.lookup("#pageHost")).lookup(".scroll-pane"); }
    private Button button(String text) { return root.lookupAll(".button").stream().filter(n -> n instanceof Button b && b.getText().equals(text)).map(n -> (Button)n).findFirst().orElseThrow(() -> new IllegalStateException("Button absent: " + text)); }
    private void assertTableCount(int expected) { TableView<?> table = (TableView<?>)root.lookup(".table-view"); require(table != null && table.getItems().size() == expected, "Unexpected visible table count: " + (table == null ? "none" : table.getItems().size())); }
    private void next() {
        if (failed || steps.isEmpty()) return;
        try { Runnable action = steps.removeFirst(); action.run(); if (!waiting && !steps.isEmpty()) after(400, this::next); }
        catch (Throwable error) { abort(error); }
    }
    private boolean waiting;
    private void await(BooleanSupplier condition, Runnable afterSuccess) {
        waiting = true; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Timeline timer = new Timeline(); timer.setCycleCount(Animation.INDEFINITE);
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(100), e -> { try { if (condition.getAsBoolean()) { timer.stop(); Platform.runLater(()->{try{afterSuccess.run();waiting=false;after(400,this::next);}catch(Throwable error){abort(error);}}); } else if (System.nanoTime() > deadline) { timer.stop(); abort(new IllegalStateException("Timed out waiting for visible UI state")); } } catch (Throwable error) { timer.stop(); abort(error); } })); timer.play();
    }
    private void after(int milliseconds, Runnable action) { PauseTransition pause = new PauseTransition(Duration.millis(milliseconds)); pause.setOnFinished(e -> Platform.runLater(action)); pause.play(); }
    private void capture(String name) {
        try {
            root.applyCss(); root.layout();
            Path folder = library.dataDirectory().resolve("flow-screenshots"); Files.createDirectories(folder);
            ImageIO.write(SwingFXUtils.fromFXImage(root.getScene().snapshot(null), null), "png", folder.resolve(name + ".png").toFile());
            long dialogs = javafx.stage.Window.getWindows().stream().filter(w -> w.isShowing() && w != stage).count(); require(dialogs == 0, "Unexpected dialog while capturing " + name);
            checks.add("Captured " + name); System.out.println("AURALIS_FLOW " + name);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private void finish() { try { Files.write(library.dataDirectory().resolve("flow-checks.txt"), checks); System.out.println("AURALIS_UI_FLOW_OK " + checks.size()); Platform.exit(); } catch (Exception e) { abort(e); } }
    private void abort(Throwable error) { failed = true; error.printStackTrace(); Platform.exit(); }
    @Override public void stop() throws Exception { if (controller != null) controller.close(); worker.shutdown(); if (!worker.awaitTermination(8, TimeUnit.SECONDS)) worker.shutdownNow(); library.close(); }
    public static void main(String[] args) { launch(args); if (failed) System.exit(1); }
    /** Plain launcher avoids Java's module-path special case for an Application main class. */
    public static final class Launcher { public static void main(String[] args) { DesktopUiFlowHarness.main(args); } }
}
