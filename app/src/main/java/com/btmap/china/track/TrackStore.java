package com.btmap.china.track;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.btmap.china.map.Mercator;

/**
 * 会话内的轨迹点存储。蓝牙线程写入，UI 线程读取绘制。
 * 最多保留 {@link #MAX_POINTS} 个点，超过后丢弃最早的点。
 */
public final class TrackStore {
    public interface Listener {
        void onTrackChanged();
    }

    public static final TrackStore INSTANCE = new TrackStore();
    public static final int MAX_POINTS = 50000;

    private static final double MIN_MOVE_METERS = 0.5;

    private double[] lats = new double[1024];
    private double[] lons = new double[1024];
    private double[] alts = new double[1024];
    private boolean[] hasAlt = new boolean[1024];
    private long[] times = new long[1024];
    private int size;
    private long droppedCount;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private TrackStore() {
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** @return 是否真的新增（过近的点会被忽略）。 */
    public boolean add(double lat, double lon, double alt, boolean altValid) {
        boolean changed = false;
        synchronized (this) {
            if (size > 0) {
                int last = size - 1;
                if (Mercator.distanceMeters(lats[last], lons[last], lat, lon) < MIN_MOVE_METERS) {
                    // 坐标相同则仅更新高度/时间，不产生新点
                    alts[last] = alt;
                    hasAlt[last] = altValid;
                    times[last] = System.currentTimeMillis();
                    return false;
                }
            }
            if (size == lats.length) {
                if (size >= MAX_POINTS) {
                    // 一次丢掉最旧的 10%，避免每来一个点就整体搬移
                    int drop = Math.max(1, MAX_POINTS / 10);
                    System.arraycopy(lats, drop, lats, 0, size - drop);
                    System.arraycopy(lons, drop, lons, 0, size - drop);
                    System.arraycopy(alts, drop, alts, 0, size - drop);
                    System.arraycopy(hasAlt, drop, hasAlt, 0, size - drop);
                    System.arraycopy(times, drop, times, 0, size - drop);
                    size -= drop;
                    droppedCount += drop;
                } else {
                    int newCap = (int) Math.min(MAX_POINTS, lats.length * 2L);
                    lats = grow(lats, newCap);
                    lons = grow(lons, newCap);
                    alts = grow(alts, newCap);
                    times = grow(times, newCap);
                    boolean[] b = new boolean[newCap];
                    System.arraycopy(hasAlt, 0, b, 0, size);
                    hasAlt = b;
                }
            }
            lats[size] = lat;
            lons[size] = lon;
            alts[size] = alt;
            hasAlt[size] = altValid;
            times[size] = System.currentTimeMillis();
            size++;
            changed = true;
        }
        if (changed) {
            for (Listener l : listeners) {
                l.onTrackChanged();
            }
        }
        return changed;
    }

    private static double[] grow(double[] src, int cap) {
        double[] out = new double[cap];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, cap));
        return out;
    }

    private static long[] grow(long[] src, int cap) {
        long[] out = new long[cap];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, cap));
        return out;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized double lat(int i) {
        return lats[i];
    }

    public synchronized double lon(int i) {
        return lons[i];
    }

    public synchronized double alt(int i) {
        return alts[i];
    }

    public synchronized boolean hasAlt(int i) {
        return hasAlt[i];
    }

    public synchronized long time(int i) {
        return times[i];
    }

    public synchronized void clear() {
        size = 0;
        droppedCount = 0;
    }

    /** 因为超出上限而被丢弃的历史点数（用于绘制端判断是否需要整体重建路径）。 */
    public synchronized long droppedCount() {
        return droppedCount;
    }

    public synchronized double[] lastLatLon() {
        if (size == 0) {
            return null;
        }
        return new double[]{lats[size - 1], lons[size - 1]};
    }
}
