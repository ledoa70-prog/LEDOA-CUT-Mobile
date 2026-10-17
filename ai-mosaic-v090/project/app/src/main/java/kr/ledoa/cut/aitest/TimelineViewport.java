package kr.ledoa.cut.aitest;

/** Timeline zoom math shared by touch handling and regression tests. */
public final class TimelineViewport {
    private long duration = 1;
    private double start = 0, span = 1;
    public void reset(long ms) { duration = Math.max(1, ms); start = 0; span = duration; }
    public double start() { return start; }
    public double span() { return span; }
    public long time(float fraction) { return Math.max(0, Math.min(duration - 1, Math.round(start + fraction * span))); }
    public long boundary(float fraction) { return Math.max(0, Math.min(duration, Math.round(start + fraction * span))); }
    public float fraction(long ms) { return (float)((ms - start) / span); }
    public void zoom(double factor, float focus) {
        focus = Math.max(0, Math.min(1, focus));
        double at = start + focus * span;
        span = Math.max(Math.min(250, duration), Math.min(duration, span / Math.max(.05, factor)));
        start = Math.max(0, Math.min(duration - span, at - focus * span));
    }
    public void pan(float fraction) { start = Math.max(0, Math.min(duration - span, start + fraction * span)); }
    public void reveal(long ms) {
        if (ms < start || ms >= start + span) start = Math.max(0, Math.min(duration - span, ms - span * .5));
    }
}
