package com.btmap.china.map;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置地图数据（assets/china_map.bin，CNMAP2 格式）读取器。
 *
 * <p>坐标以微度（1e-6°）存储，加载时投影成世界墨卡托归一化 float；每个要素带
 * detail / overview 两套 LOD 与 bbox，绘制端按缩放级别选层并按视口裁剪。</p>
 */
public final class MapData {
    public static final int LEVEL_PROVINCE = 0;
    public static final int LEVEL_CITY = 1;
    public static final int LEVEL_NINEDASH = 2;
    public static final int LEVEL_COUNTY = 3;

    private static final byte[] MAGIC = {'C', 'N', 'M', 'A', 'P', '2'};
    private static final int NO_CENTER = Integer.MIN_VALUE;

    public static final class RingSet {
        public final float[] xs;
        public final float[] ys;
        public final int[] start;
        public final int[] len;

        RingSet(float[] xs, float[] ys, int[] start, int[] len) {
            this.xs = xs;
            this.ys = ys;
            this.start = start;
            this.len = len;
        }
    }

    public static final class Feature {
        public final int level;
        public final String name;
        public final double centerLon;
        public final double centerLat;
        public final RingSet detail;
        public final RingSet overview;
        /** 世界墨卡托坐标下的包围盒。 */
        public final float minX;
        public final float minY;
        public final float maxX;
        public final float maxY;

        Feature(int level, String name, double centerLon, double centerLat,
                RingSet detail, RingSet overview,
                float minX, float minY, float maxX, float maxY) {
            this.level = level;
            this.name = name;
            this.centerLon = centerLon;
            this.centerLat = centerLat;
            this.detail = detail;
            this.overview = overview;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public boolean hasCenter() {
            return !Double.isNaN(centerLon) && !Double.isNaN(centerLat);
        }
    }

    public final Feature[] features;
    public final float minX;
    public final float minY;
    public final float maxX;
    public final float maxY;

    private MapData(Feature[] features, float minX, float minY, float maxX, float maxY) {
        this.features = features;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public static MapData load(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("地图数据格式错误：magic 不匹配（需要 CNMAP2）");
            }
        }
        int count = in.readInt();
        if (count <= 0 || count > 10000) {
            throw new IOException("地图数据格式错误：要素数量异常 " + count);
        }
        in.readFloat();
        in.readFloat();
        in.readFloat();
        in.readFloat();

        Feature[] features = new Feature[count];
        float gMinX = Float.MAX_VALUE, gMinY = Float.MAX_VALUE;
        float gMaxX = -Float.MAX_VALUE, gMaxY = -Float.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            int level = in.readUnsignedByte();
            in.readUnsignedByte();
            int centerLonUd = in.readInt();
            int centerLatUd = in.readInt();
            double centerLon = centerLonUd == NO_CENTER ? Double.NaN : centerLonUd / 1e6;
            double centerLat = centerLatUd == NO_CENTER ? Double.NaN : centerLatUd / 1e6;
            int nameLen = in.readUnsignedShort();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int bMinLon = in.readInt();
            int bMinLat = in.readInt();
            int bMaxLon = in.readInt();
            int bMaxLat = in.readInt();
            int ringCount = in.readUnsignedShort();

            RingSet detail = readRings(in, ringCount);
            RingSet overview = readRings(in, ringCount);

            float minX = (float) Mercator.lonToX(bMinLon / 1e6);
            float minY = (float) Mercator.latToY(bMaxLat / 1e6);
            float maxX = (float) Mercator.lonToX(bMaxLon / 1e6);
            float maxY = (float) Mercator.latToY(bMinLat / 1e6);
            if (minX < gMinX) gMinX = minX;
            if (minY < gMinY) gMinY = minY;
            if (maxX > gMaxX) gMaxX = maxX;
            if (maxY > gMaxY) gMaxY = maxY;

            features[i] = new Feature(level, name, centerLon, centerLat,
                    detail, overview, minX, minY, maxX, maxY);
        }
        return new MapData(features, gMinX, gMinY, gMaxX, gMaxY);
    }

    private static RingSet readRings(DataInputStream in, int ringCount) throws IOException {
        int[] start = new int[ringCount];
        int[] len = new int[ringCount];
        float[] xs = new float[1024];
        float[] ys = new float[1024];
        int total = 0;
        for (int r = 0; r < ringCount; r++) {
            int n = in.readInt();
            if (n <= 0 || n > 2_000_000) {
                throw new IOException("地图数据损坏：环点数 " + n);
            }
            if (total + n > xs.length) {
                int cap = Math.max(total + n, xs.length * 2);
                float[] nx = new float[cap];
                float[] ny = new float[cap];
                System.arraycopy(xs, 0, nx, 0, total);
                System.arraycopy(ys, 0, ny, 0, total);
                xs = nx;
                ys = ny;
            }
            start[r] = total;
            len[r] = n;
            for (int k = 0; k < n; k++) {
                float lon = in.readInt() / 1e6f;
                float lat = in.readInt() / 1e6f;
                xs[total] = (float) Mercator.lonToX(lon);
                ys[total] = (float) Mercator.latToY(lat);
                total++;
            }
        }
        float[] tx = new float[total];
        float[] ty = new float[total];
        System.arraycopy(xs, 0, tx, 0, total);
        System.arraycopy(ys, 0, ty, 0, total);
        return new RingSet(tx, ty, start, len);
    }

    public static MapData loadFile(String path) throws IOException {
        try (FileInputStream in = new FileInputStream(path)) {
            return load(in);
        }
    }

    public int countLevel(int level) {
        int n = 0;
        for (Feature f : features) {
            if (f.level == level) {
                n++;
            }
        }
        return n;
    }
}
