package kr.ledoa.cut.aitest;

import java.util.*;

/** User-authored geometry, independent of detector samples and identity state. */
public final class ManualTrack {
    public final int id;
    public final boolean replacesSelected;
    private long startMs, endMs;
    private final TreeMap<Long, FacePath.Box> points = new TreeMap<>();

    public ManualTrack(int id, boolean replacesSelected, long start, long end) {
        if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid manual range");
        this.id = id; this.replacesSelected = replacesSelected; startMs = start; endMs = end;
    }
    public synchronized long start() { return startMs; }
    public synchronized long end() { return endMs; }
    public synchronized boolean contains(long ms) { return ms >= startMs && ms < endMs; }
    public synchronized void range(long start, long end, long duration) {
        if (start < 0 || end <= start || end > duration) throw new IllegalArgumentException("Invalid manual range");
        // Trimming is reversible. Hidden keyframes remain available when extended again.
        startMs = start; endMs = end;
    }
    public synchronized void put(long ms, FacePath.Box box) {
        if (ms < 0 || box == null || !Float.isFinite(box.x) || !Float.isFinite(box.y) ||
            !Float.isFinite(box.w) || !Float.isFinite(box.h) || box.w <= 0 || box.h <= 0 ||
            box.x < 0 || box.y < 0 || box.x + box.w > 1.00001f || box.y + box.h > 1.00001f)
            throw new IllegalArgumentException("Invalid manual box");
        // A manual mask never needs face embeddings, including number plates.
        points.put(ms, new FacePath.Box(box.x, box.y, box.w, box.h, -1));
    }
    public synchronized boolean remove(long ms) { return points.remove(ms) != null; }
    public synchronized int size() { return points.size(); }
    public synchronized NavigableMap<Long, FacePath.Box> points() {
        return Collections.unmodifiableNavigableMap(new TreeMap<>(points));
    }
    public synchronized Long previous(long ms) { return points.lowerKey(ms); }
    public synchronized Long next(long ms) { return points.higherKey(ms); }
    public synchronized Long near(long ms, long tolerance) {
        Long a = points.floorKey(ms), b = points.ceilingKey(ms);
        Long n = a == null ? b : b == null ? a : ms - a <= b - ms ? a : b;
        return n != null && Math.abs(n - ms) <= tolerance ? n : null;
    }
    public synchronized FacePath.Box at(long ms, Collection<Long> sceneCuts) {
        if (!contains(ms) || points.isEmpty()) return null;
        long shotStart = 0, shotEnd = Long.MAX_VALUE;
        for (long cut : sceneCuts) {
            if (cut <= ms) shotStart = Math.max(shotStart, cut);
            else shotEnd = Math.min(shotEnd, cut);
        }
        Map.Entry<Long, FacePath.Box> a = points.floorEntry(ms), b = points.ceilingEntry(ms);
        // Never carry a user box or interpolate into another detected shot.
        if (a != null && a.getKey() < shotStart) a = null;
        if (b != null && b.getKey() >= shotEnd) b = null;
        if (a == null) return b == null ? null : b.getValue();
        if (b == null || a.getKey().equals(b.getKey())) return a.getValue();
        float f = (ms - a.getKey()) / (float)(b.getKey() - a.getKey());
        FacePath.Box x = a.getValue(), y = b.getValue();
        return new FacePath.Box(x.x + (y.x-x.x)*f, x.y + (y.y-x.y)*f,
            x.w + (y.w-x.w)*f, x.h + (y.h-x.h)*f, -1);
    }
    public synchronized ManualTrack copy() {
        ManualTrack t = new ManualTrack(id, replacesSelected, startMs, endMs);
        t.points.putAll(points); return t;
    }
    public synchronized ManualTrack split(long ms, int newId, Collection<Long> cuts) {
        if (ms <= startMs || ms >= endMs) throw new IllegalArgumentException("Split outside range");
        FacePath.Box seed = at(ms, cuts);
        ManualTrack right = new ManualTrack(newId, replacesSelected, ms, endMs);
        right.points.putAll(points.tailMap(ms, true));
        points.tailMap(ms, true).clear();
        if (seed != null) { points.put(ms, seed); right.points.put(ms, seed); }
        endMs = ms; return right;
    }
}
