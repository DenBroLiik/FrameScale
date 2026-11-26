package by.xopilot.framescale;

import java.util.Arrays;

public final class FrameStats {
    private final double[] samples;
    private int size, next;
    public FrameStats(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        samples = new double[capacity];
    }
    public void add(double ms) {
        if (!Double.isFinite(ms) || ms <= 0) return;
        samples[next] = ms;
        next = (next + 1) % samples.length;
        size = Math.min(size + 1, samples.length);
    }
    public void clear() { size = next = 0; }
    public record Snapshot(int count, double meanMs, double p95Ms, double fps, double low1Fps) {}
    public Snapshot snapshot() {
        if (size == 0) return new Snapshot(0, 0, 0, 0, 0);
        double[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        double sum = 0, slow = 0;
        for (double v : sorted) sum += v;
        int tail = Math.max(1, (int)Math.ceil(size * .01));
        for (int i = size - tail; i < size; i++) slow += sorted[i];
        double mean = sum / size;
        return new Snapshot(size, mean, sorted[(int)Math.ceil(size * .95) - 1], 1000 / mean, 1000 * tail / slow);
    }
}
