package io.auralis.desktop.playback;

import java.util.function.LongSupplier;

/** Monotonic timer; UI ticks apply gain without changing the user's volume. */
public final class SleepTimer {
    private final LongSupplier clock;
    private long deadline;
    public SleepTimer() { this(() -> System.nanoTime() / 1_000_000); }
    public SleepTimer(LongSupplier clock) { this.clock = clock; }
    public void start(int minutes) {
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Minutes must be 1..1440");
        deadline = clock.getAsLong() + minutes * 60_000L;
    }
    public void cancel() { deadline = 0; }
    public boolean active() { return deadline != 0; }
    public long remainingMillis() { return active() ? Math.max(0, deadline - clock.getAsLong()) : 0; }
    public double gain() { return active() ? Math.clamp(remainingMillis() / 10_000.0, 0, 1) : 1; }
    public boolean expired() { return active() && remainingMillis() == 0; }
}
