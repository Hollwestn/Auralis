package io.auralis.desktop.visualization;

import java.util.Arrays;

/** Bounded audio features from actual MediaPlayer dB bands. No clock-generated substitute signal. */
public final class AudioEnvelope {
    public static final int BANDS = 64;
    public record Frame(double bass, double mid, double treble, double energy, double onset, double[] bands) {
        public Frame { bands = bands.clone(); }
        @Override public double[] bands() { return bands.clone(); }
    }
    private final double[] target = new double[BANDS], smooth = new double[BANDS], previous = new double[BANDS];
    private boolean playing;
    private double age = 10, onset, pendingOnset, bassTarget, bass;
    public synchronized void setPlaying(boolean value) { playing = value; if (!value) { Arrays.fill(target, 0); Arrays.fill(previous, 0); pendingOnset = 0; bassTarget = 0; } }
    public synchronized void accept(float[] magnitudes, double timestamp) {
        if (!playing || magnitudes == null || magnitudes.length == 0 || !Double.isFinite(timestamp)) return;
        // Preserve the narrow low-frequency peak before reducing the display to 64 bands.
        // Averaging the first four display bands used to dilute bass with mid frequencies.
        double low = 0;
        for (int i=0;i<Math.max(1,(int)Math.ceil(magnitudes.length*.012));i++) if(Float.isFinite(magnitudes[i])) low=Math.max(low,Math.clamp((magnitudes[i]+60.0)/60.0,0,1));
        bassTarget = Math.pow(low,.72);
        double flux = 0;
        for (int i = 0; i < BANDS; i++) {
            int first = i * magnitudes.length / BANDS, end = Math.max(first + 1, (i + 1) * magnitudes.length / BANDS);
            double value = 0;
            for (int j = first; j < Math.min(end, magnitudes.length); j++) if (Float.isFinite(magnitudes[j])) value = Math.max(value, Math.clamp((magnitudes[j] + 60.0) / 60.0, 0, 1));
            target[i] = value; flux += Math.max(0, value - previous[i]); previous[i] = value;
        }
        pendingOnset = Math.max(pendingOnset, Math.clamp(flux / BANDS * 4, 0, 1)); age = 0;
    }
    public synchronized Frame step(double elapsedSeconds) {
        double dt = Math.clamp(Double.isFinite(elapsedSeconds) ? elapsedSeconds : 0, 0, .25); age += dt;
        if (!playing || age > .35) { Arrays.fill(target, 0); bassTarget=0; }
        bass += (bassTarget-bass)*(1-Math.exp(-dt/(bassTarget>bass?.055:.28)));
        double squares = 0;
        for (int i = 0; i < BANDS; i++) { double tau = target[i] > smooth[i] ? .035 : .24; smooth[i] += (target[i] - smooth[i]) * (1 - Math.exp(-dt / tau)); squares += smooth[i] * smooth[i]; }
        onset = Math.max(pendingOnset, onset * Math.exp(-dt / .13)); pendingOnset = 0;
        return new Frame(bass, mean(4, 24), mean(24, BANDS), Math.sqrt(squares / BANDS), onset, smooth);
    }
    private double mean(int from, int to) { double total = 0; for (int i = from; i < to; i++) total += smooth[i]; return total / (to - from); }
}
