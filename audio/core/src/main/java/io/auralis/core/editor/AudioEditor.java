package io.auralis.core.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.core.audio.FfmpegService;
import io.auralis.core.editor.EditProject.Clip;
import io.auralis.core.library.LibraryService;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Source-timeline editing with real FFmpeg rendering. Range coordinates precede global speed/crossfade. */
public final class AudioEditor {
    public enum ExportFormat { WAV, FLAC, MP3 }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FfmpegService audio;
    public AudioEditor() { this(new FfmpegService()); }
    public AudioEditor(FfmpegService audio) { this.audio = Objects.requireNonNull(audio); }
    public EditProject open(Path source) throws IOException { return EditProject.of(source, audio.probe(source).durationSeconds()); }
    public EditProject trim(EditProject project, double from, double to) { validateRange(project, from, to); return project.withClips(slice(project.clips(), from, to)); }
    public EditProject deleteRange(EditProject project, double from, double to) {
        validateRange(project, from, to);
        List<Clip> result = new ArrayList<>(slice(project.clips(), 0, from)); result.addAll(slice(project.clips(), to, project.sourceTimelineSeconds()));
        if (result.isEmpty()) throw new IllegalArgumentException("Нельзя удалить весь проект");
        return project.withClips(result);
    }
    public EditProject insert(EditProject project, double at, Path source, double sourceFrom, double sourceTo) throws IOException {
        var info = audio.probe(source);
        if (sourceFrom < 0 || sourceTo <= sourceFrom || sourceTo > info.durationSeconds() + 0.001) throw new IllegalArgumentException("Некорректный диапазон вставки");
        return insertClips(project, at, List.of(new Clip(source, sourceFrom, Math.min(sourceTo, info.durationSeconds()), 0, 0, 0)));
    }
    public EditProject replace(EditProject project, double from, double to, Path source, double sourceFrom, double sourceTo) throws IOException {
        validateRange(project, from, to);
        var info = audio.probe(source);
        if (sourceFrom < 0 || sourceTo <= sourceFrom || sourceTo > info.durationSeconds() + 0.001) throw new IllegalArgumentException("Некорректный диапазон замены");
        List<Clip> result = new ArrayList<>(slice(project.clips(), 0, from));
        result.add(new Clip(source, sourceFrom, Math.min(sourceTo, info.durationSeconds()), 0, 0, 0));
        result.addAll(slice(project.clips(), to, project.sourceTimelineSeconds())); return project.withClips(result);
    }
    public EditProject copyRange(EditProject project, double from, double to, double insertAt) { validateRange(project, from, to); return insertClips(project, insertAt, slice(project.clips(), from, to)); }
    public EditProject moveRange(EditProject project, double from, double to, double insertAt) {
        validateRange(project, from, to); validatePosition(project, insertAt);
        if (insertAt >= from && insertAt <= to) return project;
        List<Clip> copied = slice(project.clips(), from, to);
        return insertClips(deleteRange(project, from, to), insertAt > to ? insertAt - (to - from) : insertAt, copied);
    }
    public EditProject gainRange(EditProject project, double from, double to, double decibels) {
        validateRange(project, from, to);
        List<Clip> result = new ArrayList<>(slice(project.clips(), 0, from));
        for (Clip clip : slice(project.clips(), from, to)) result.add(new Clip(clip.source(), clip.fromSeconds(), clip.toSeconds(), decibels, clip.fadeInSeconds(), clip.fadeOutSeconds()));
        result.addAll(slice(project.clips(), to, project.sourceTimelineSeconds())); return project.withClips(result);
    }
    public EditProject fadeRange(EditProject project, double from, double to, double fadeIn, double fadeOut) {
        validateRange(project, from, to);
        List<Clip> selection = slice(project.clips(), from, to);
        // A range spanning source cuts cannot be represented as a single edge fade without rendering.
        if (selection.size() != 1) throw new IllegalArgumentException("Fade диапазона поддерживается внутри одного фрагмента; для всего проекта используйте общие Fade In/Out");
        Clip clip = selection.getFirst();
        List<Clip> result = new ArrayList<>(slice(project.clips(), 0, from));
        result.add(new Clip(clip.source(), clip.fromSeconds(), clip.toSeconds(), clip.gainDb(), fadeIn, fadeOut));
        result.addAll(slice(project.clips(), to, project.sourceTimelineSeconds())); return project.withClips(result);
    }
    private EditProject insertClips(EditProject project, double at, List<Clip> inserted) {
        validatePosition(project, at);
        List<Clip> result = new ArrayList<>(slice(project.clips(), 0, at)); result.addAll(inserted); result.addAll(slice(project.clips(), at, project.sourceTimelineSeconds())); return project.withClips(result);
    }
    private static List<Clip> slice(List<Clip> clips, double from, double to) {
        List<Clip> result = new ArrayList<>(); double cursor = 0;
        for (Clip clip : clips) {
            double end = cursor + clip.durationSeconds(); double startOverlap = Math.max(from, cursor), endOverlap = Math.min(to, end);
            if (endOverlap - startOverlap > 0.0000001) result.add(clip.slice(startOverlap - cursor, endOverlap - cursor)); cursor = end;
        }
        return result;
    }
    private static void validateRange(EditProject project, double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || to <= from || to > project.sourceTimelineSeconds() + 0.000001) throw new IllegalArgumentException("Диапазон должен находиться внутри исходной шкалы проекта");
    }
    private static void validatePosition(EditProject project, double at) { if (!Double.isFinite(at) || at < 0 || at > project.sourceTimelineSeconds() + 0.000001) throw new IllegalArgumentException("Позиция вне проекта"); }
    public Path export(EditProject project, Path destination, ExportFormat format) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        for (Clip clip : project.clips()) {
            if (clip.source().equals(target) || Files.exists(target) && Files.isSameFile(clip.source(), target)) throw new IOException("Экспорт не может перезаписывать исходное аудио");
            if (!Files.isRegularFile(clip.source())) throw new IOException("Исходный файл не найден: " + clip.source());
        }
        if (Files.exists(target)) throw new FileAlreadyExistsException("Файл уже существует; выберите новое имя: " + target.getFileName());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".auralis-render-", "." + format.name().toLowerCase(Locale.ROOT));
        List<String> command = new ArrayList<>(List.of(audio.executable(), "-nostdin", "-v", "error", "-y"));
        Map<Path, Integer> inputs = new LinkedHashMap<>();
        for (Clip clip : project.clips()) inputs.computeIfAbsent(clip.source(), ignored -> inputs.size());
        Map<Path, Integer> channels = new HashMap<>();
        for (Path input : inputs.keySet()) { command.add("-i"); command.add(input.toString()); channels.put(input, audio.probe(input).channels()); }
        command.addAll(List.of("-filter_complex", filterGraph(project, inputs, channels), "-map", "[out]", "-vn", "-map_metadata", "-1"));
        command.addAll(switch (format) {
            case WAV -> List.of("-c:a", "pcm_s24le");
            case FLAC -> List.of("-c:a", "flac", "-compression_level", "8");
            case MP3 -> List.of("-c:a", "libmp3lame", "-q:a", "2");
        });
        command.add(temporary.toString());
        try {
            FfmpegService.run(command, Duration.ofSeconds(Math.min(7200, Math.max(120, (long) project.sourceTimelineSeconds() * 4))));
            var info = audio.probe(temporary);
            if (Math.abs(info.durationSeconds() - project.durationSeconds()) > Math.max(0.15, project.durationSeconds() * 0.005)) throw new IOException("Проверка экспорта: неожиданная длительность аудио");
            Files.move(temporary, target); return target;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String filterGraph(EditProject project, Map<Path, Integer> inputs, Map<Path, Integer> channels) {
        StringBuilder graph = new StringBuilder(); int count = project.clips().size();
        for (int i = 0; i < count; i++) {
            Clip clip = project.clips().get(i);
            graph.append('[').append(inputs.get(clip.source())).append(":a:0]atrim=start=").append(number(clip.fromSeconds())).append(":end=").append(number(clip.toSeconds()))
                    .append(",asetpts=PTS-STARTPTS");
            // FFmpeg's default mono->stereo matrix attenuates by 3 dB. Duplicate mono explicitly.
            if (channels.get(clip.source()) == 1) graph.append(",pan=stereo|c0=c0|c1=c0");
            graph.append(",aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo");
            if (clip.gainDb() != 0) graph.append(",volume=").append(number(clip.gainDb())).append("dB");
            if (clip.fadeInSeconds() > 0) graph.append(",afade=t=in:st=0:d=").append(number(clip.fadeInSeconds()));
            if (clip.fadeOutSeconds() > 0) graph.append(",afade=t=out:st=").append(number(clip.durationSeconds() - clip.fadeOutSeconds())).append(":d=").append(number(clip.fadeOutSeconds()));
            graph.append("[clip").append(i).append("]; ");
        }
        if (count == 1) graph.append("[clip0]anull[joined]; ");
        else if (project.crossfadeSeconds() == 0) {
            for (int i = 0; i < count; i++) graph.append("[clip").append(i).append(']');
            graph.append("concat=n=").append(count).append(":v=0:a=1[joined]; ");
        } else {
            String previous = "clip0";
            for (int i = 1; i < count; i++) {
                String output = i == count - 1 ? "joined" : "cross" + i;
                graph.append('[').append(previous).append("][clip").append(i).append("]acrossfade=d=").append(number(project.crossfadeSeconds())).append(":c1=tri:c2=tri[").append(output).append("]; "); previous = output;
            }
        }
        graph.append("[joined]anull");
        if (project.pitchSemitones() != 0) {
            double pitchFactor = Math.pow(2, project.pitchSemitones() / 12);
            graph.append(",asetrate=").append(number(48000 * pitchFactor)).append(",aresample=48000");
            appendTempo(graph, project.speed() / pitchFactor);
        } else appendTempo(graph, project.speed());
        if (project.gainDb() != 0) graph.append(",volume=").append(number(project.gainDb())).append("dB");
        for (EditProject.Effect effect : project.effects()) graph.append(switch (effect) {
            case LIMITER -> ",alimiter=limit=0.98:level=false";
            case COMPRESSOR -> ",acompressor=threshold=0.125:ratio=3:attack=20:release=250:makeup=1";
            case NORMALIZE -> ",loudnorm=I=-16:TP=-1.5:LRA=11";
            case REVERB -> ",aecho=0.8:0.7:45|90:0.18|0.12";
            case DELAY -> ",aecho=0.8:0.7:250|500:0.3|0.15";
        });
        // Echo tails are deliberately cut to the project boundary so rendered versions retain their timeline.
        if (project.fadeInSeconds() > 0) graph.append(",afade=t=in:st=0:d=").append(number(project.fadeInSeconds()));
        if (project.fadeOutSeconds() > 0) graph.append(",afade=t=out:st=").append(number(project.durationSeconds() - project.fadeOutSeconds())).append(":d=").append(number(project.fadeOutSeconds()));
        graph.append(",atrim=duration=").append(number(project.durationSeconds())).append(",asetpts=PTS-STARTPTS[out]"); return graph.toString();
    }
    private static void appendTempo(StringBuilder graph, double tempo) {
        while (tempo < 0.5) { graph.append(",atempo=0.5"); tempo /= 0.5; }
        while (tempo > 2) { graph.append(",atempo=2"); tempo /= 2; }
        if (Math.abs(tempo - 1) > 0.000001) graph.append(",atempo=").append(number(tempo));
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.9f", value); }
    public void saveProject(EditProject project, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        for (Clip clip : project.clips()) if (target.equals(clip.source()) || Files.exists(target) && Files.isSameFile(clip.source(), target)) throw new IOException("Проект нельзя сохранять поверх исходного аудио");
        ObjectNode root = JSON.createObjectNode(); root.put("version", 1);
        root.put("gainDb", project.gainDb()); root.put("speed", project.speed()); root.put("pitchSemitones", project.pitchSemitones()); root.put("fadeInSeconds", project.fadeInSeconds()); root.put("fadeOutSeconds", project.fadeOutSeconds()); root.put("crossfadeSeconds", project.crossfadeSeconds());
        ArrayNode clips = root.putArray("clips");
        for (Clip clip : project.clips()) {
            ObjectNode node = clips.addObject(); node.put("source", clip.source().toString()); node.put("fromSeconds", clip.fromSeconds()); node.put("toSeconds", clip.toSeconds()); node.put("gainDb", clip.gainDb()); node.put("fadeInSeconds", clip.fadeInSeconds()); node.put("fadeOutSeconds", clip.fadeOutSeconds());
        }
        ArrayNode effects = root.putArray("effects"); project.effects().forEach(effect -> effects.add(effect.name()));
        LibraryService.writeAtomically(destination, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
    public EditProject loadProject(Path source) throws IOException {
        if (Files.size(source) > 4 * 1024 * 1024) throw new IOException("Слишком большой файл проекта");
        JsonNode root = JSON.readTree(Files.readString(source));
        if (root.path("version").asInt() != 1) throw new IOException("Неподдерживаемая версия проекта");
        List<Clip> clips = new ArrayList<>();
        try {
            for (JsonNode node : root.path("clips")) {
                Path input = Path.of(node.path("source").asText()); if (!Files.isRegularFile(input)) throw new IOException("Исходный файл проекта отсутствует: " + input);
                clips.add(new Clip(input, node.path("fromSeconds").asDouble(), node.path("toSeconds").asDouble(), node.path("gainDb").asDouble(), node.path("fadeInSeconds").asDouble(), node.path("fadeOutSeconds").asDouble()));
            }
            List<EditProject.Effect> effects = new ArrayList<>(); for (JsonNode node : root.path("effects")) effects.add(EditProject.Effect.valueOf(node.asText()));
            return new EditProject(clips, root.path("gainDb").asDouble(), root.path("speed").asDouble(1), root.path("pitchSemitones").asDouble(), root.path("fadeInSeconds").asDouble(), root.path("fadeOutSeconds").asDouble(), root.path("crossfadeSeconds").asDouble(), effects);
        } catch (IllegalArgumentException e) { throw new IOException("Повреждённый файл проекта", e); }
    }
}
