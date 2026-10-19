package io.auralis.core.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Non-destructive source references and processing parameters; no audio data is stored here. */
public record EditProject(List<Clip> clips, double gainDb, double speed, double pitchSemitones,
                          double fadeInSeconds, double fadeOutSeconds, double crossfadeSeconds,
                          List<Effect> effects) {
    public enum Effect { LIMITER, COMPRESSOR, NORMALIZE, REVERB, DELAY }
    public record Clip(Path source, double fromSeconds, double toSeconds, double gainDb,
                       double fadeInSeconds, double fadeOutSeconds) {
        public Clip {
            Objects.requireNonNull(source); source = source.toAbsolutePath().normalize();
            if (!finite(fromSeconds, toSeconds, gainDb, fadeInSeconds, fadeOutSeconds) || fromSeconds < 0 || toSeconds <= fromSeconds || gainDb < -60 || gainDb > 24 || fadeInSeconds < 0 || fadeOutSeconds < 0) throw new IllegalArgumentException("Некорректные параметры фрагмента");
            if (fadeInSeconds > toSeconds - fromSeconds || fadeOutSeconds > toSeconds - fromSeconds) throw new IllegalArgumentException("Fade превышает длину фрагмента");
        }
        public double durationSeconds() { return toSeconds - fromSeconds; }
        public Clip slice(double from, double to) {
            double duration = to - from;
            // Trimming away an edge also removes the fade associated with that source edge.
            return new Clip(source, fromSeconds + from, fromSeconds + to, gainDb,
                    from == 0 ? Math.min(fadeInSeconds, duration) : 0,
                    Math.abs(to - durationSeconds()) < 0.000001 ? Math.min(fadeOutSeconds, duration) : 0);
        }
    }
    public EditProject {
        clips = List.copyOf(clips); effects = List.copyOf(effects);
        if (clips.isEmpty()) throw new IllegalArgumentException("Проект должен содержать хотя бы один фрагмент");
        if (!finite(gainDb, speed, pitchSemitones, fadeInSeconds, fadeOutSeconds, crossfadeSeconds) || gainDb < -60 || gainDb > 24 || speed < 0.25 || speed > 4 || pitchSemitones < -24 || pitchSemitones > 24 || fadeInSeconds < 0 || fadeOutSeconds < 0 || crossfadeSeconds < 0) throw new IllegalArgumentException("Некорректные параметры обработки");
        if (fadeInSeconds > durationSeconds(clips, speed, crossfadeSeconds) || fadeOutSeconds > durationSeconds(clips, speed, crossfadeSeconds)) throw new IllegalArgumentException("Fade превышает длительность проекта");
        if (crossfadeSeconds > 0) for (Clip clip : clips) if (crossfadeSeconds * 2 > clip.durationSeconds()) throw new IllegalArgumentException("Crossfade слишком длинный для фрагмента");
    }
    public static EditProject of(Path source, double duration) { return new EditProject(List.of(new Clip(source, 0, duration, 0, 0, 0)), 0, 1, 0, 0, 0, 0, List.of()); }
    public double sourceTimelineSeconds() { return clips.stream().mapToDouble(Clip::durationSeconds).sum(); }
    public double durationSeconds() { return durationSeconds(clips, speed, crossfadeSeconds); }
    private static double durationSeconds(List<Clip> clips, double speed, double crossfade) { return (clips.stream().mapToDouble(Clip::durationSeconds).sum() - crossfade * (clips.size() - 1)) / speed; }
    private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
    public EditProject withClips(List<Clip> value) {
        // Timeline editing clears global edge fades/crossfades so removed material cannot invalidate them.
        return new EditProject(value, gainDb, speed, pitchSemitones, 0, 0, 0, effects);
    }
    public EditProject withGainDb(double value) { return new EditProject(clips, value, speed, pitchSemitones, fadeInSeconds, fadeOutSeconds, crossfadeSeconds, effects); }
    public EditProject withSpeed(double value) { return new EditProject(clips, gainDb, value, pitchSemitones, 0, 0, crossfadeSeconds, effects); }
    public EditProject withPitchSemitones(double value) { return new EditProject(clips, gainDb, speed, value, fadeInSeconds, fadeOutSeconds, crossfadeSeconds, effects); }
    public EditProject withFades(double in, double out) { return new EditProject(clips, gainDb, speed, pitchSemitones, in, out, crossfadeSeconds, effects); }
    public EditProject withCrossfade(double seconds) { return new EditProject(clips, gainDb, speed, pitchSemitones, fadeInSeconds, fadeOutSeconds, seconds, effects); }
    public EditProject withEffects(List<Effect> value) { return new EditProject(clips, gainDb, speed, pitchSemitones, fadeInSeconds, fadeOutSeconds, crossfadeSeconds, value); }
}
