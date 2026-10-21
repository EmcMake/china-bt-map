package com.btmap.china.track;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.btmap.china.map.Mercator;

/**
 * 会话内的轨迹点存储。蓝牙线程写入，UI 线程读取绘制。
 *
 * <p>每个点保存：原始经纬度、输入坐标系（datum）、精度、底图 GCJ-02 坐标、时间。
 * 切换坐标系可只影响新点，也可以调用 {@link #redatumAll(int)} 重算全部点。</p>
 */
public final class TrackStore {
    public interface Listener {
        void onTrackChanged();
    }

    public static final TrackStore INSTANCE = new TrackStore();
    public static final int MAX_POINTS = 50000;

    private static final double MIN_MOVE_METERS = 0.5;

    private double[] rawLats = new double[1024];
    private double[] rawLons = new double[1024];
    private double[] mapLats = new double[1024];
    private double[] mapLons = new double[1024];
    private double[] alts = new double[1024];
    private float[] accuracies = new float[1024];
    private boolean[] hasAlt = new boolean[1024];
    private byte[] datums = new byte[1024];
    private long[] times = new long[1024];
    private int size;
    private long droppedCount;
    private int datumEpoch;

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
    public boolean add(double lat, double lon, double alt, boolean altValid,
                       double accuracy, int datum) {
        double[] map = CoordSystems.toMap(datum, lat, lon);
        boolean changed;
        synchronized (this) {
            if (size > 0) {
                int last = size - 1;
                if (Mercator.distanceMeters(mapLats[last], mapLons[last], map[0], map[1])
                        < MIN_MOVE_METERS) {
                    alts[last] = alt;
                    hasAlt[last] = altValid;
                    accuracies[last] = (float) accuracy;
                    times[last] = System.currentTimeMillis();
                    return false;
                }
            }
            ensureCapacity(size + 1);
            if (size >= MAX_POINTS) {
                dropOldest(MAX_POINTS / 10);
            }
            rawLats[size] = lat;
            rawLons[size] = lon;
            mapLats[size] = map[0];
            mapLons[size] = map[1];
            alts[size] = alt;
            hasAlt[size] = altValid;
            accuracies[size] = (float) accuracy;
            datums[size] = (byte) datum;
            times[size] = System.currentTimeMillis();
            size++;
            changed = true;
        }
        if (changed) {
            notifyChanged();
        }
        return changed;
    }

    private void dropOldest(int drop) {
        System.arraycopy(rawLats, drop, rawLats, 0, size - drop);
        System.arraycopy(rawLons, drop, rawLons, 0, size - drop);
        System.arraycopy(mapLats, drop, mapLats, 0, size - drop);
        System.arraycopy(mapLons, drop, mapLons, 0, size - drop);
        System.arraycopy(alts, drop, alts, 0, size - drop);
        System.arraycopy(accuracies, drop, accuracies, 0, size - drop);
        System.arraycopy(hasAlt, drop, hasAlt, 0, size - drop);
        System.arraycopy(datums, drop, datums, 0, size - drop);
        System.arraycopy(times, drop, times, 0, size - drop);
        size -= drop;
        droppedCount += drop;
    }

    private void ensureCapacity(int need) {
        if (need <= rawLats.length) {
            return;
        }
        int cap = (int) Math.min(MAX_POINTS, Math.max(need, rawLats.length * 2L));
        rawLats = grow(rawLats, cap);
        rawLons = grow(rawLons, cap);
        mapLats = grow(mapLats, cap);
        mapLons = grow(mapLons, cap);
        alts = grow(alts, cap);
        accuracies = grow(accuracies, cap);
        times = grow(times, cap);
        boolean[] b = new boolean[cap];
        System.arraycopy(hasAlt, 0, b, 0, size);
        hasAlt = b;
        byte[] d = new byte[cap];
        System.arraycopy(datums, 0, d, 0, size);
        datums = d;
    }

    private static double[] grow(double[] src, int cap) {
        double[] out = new double[cap];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, cap));
        return out;
    }

    private static float[] grow(float[] src, int cap) {
        float[] out = new float[cap];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, cap));
        return out;
    }

    private static long[] grow(long[] src, int cap) {
        long[] out = new long[cap];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, cap));
        return out;
    }

    /** 保留原始坐标，按新坐标系重算所有点的底图坐标。 */
    public void redatumAll(int datum) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                double[] map = CoordSystems.toMap(datum, rawLats[i], rawLons[i]);
                mapLats[i] = map[0];
                mapLons[i] = map[1];
                datums[i] = (byte) datum;
            }
            datumEpoch++;
        }
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            l.onTrackChanged();
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized int datumEpoch() {
        return datumEpoch;
    }

    public synchronized long droppedCount() {
        return droppedCount;
    }

    public synchronized double rawLat(int i) {
        return rawLats[i];
    }

    public synchronized double rawLon(int i) {
        return rawLons[i];
    }

    public synchronized double mapLat(int i) {
        return mapLats[i];
    }

    public synchronized double mapLon(int i) {
        return mapLons[i];
    }

    public synchronized double alt(int i) {
        return alts[i];
    }

    public synchronized boolean hasAlt(int i) {
        return hasAlt[i];
    }

    public synchronized double accuracy(int i) {
        return accuracies[i];
    }

    public synchronized int datum(int i) {
        return datums[i];
    }

    public synchronized long time(int i) {
        return times[i];
    }

    public synchronized void clear() {
        size = 0;
        droppedCount = 0;
        datumEpoch++;
    }

    /** 最后一点的底图坐标 {lat, lon}。 */
    public synchronized double[] lastLatLon() {
        if (size == 0) {
            return null;
        }
        return new double[]{mapLats[size - 1], mapLons[size - 1]};
    }
}
