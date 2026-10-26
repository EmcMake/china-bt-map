package com.btmap.china.map;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 世界地图数据（assets/world_map.bin，WRLD1 格式）。
 *
 * <p>Natural Earth 国家 / 省州 / 城市，双 LOD、英文名 + 中文名、bbox、中心点。
 * 坐标用微度存储，加载后转成 Web 墨卡托归一化坐标。</p>
 */
public final class WorldData {
    public static final int LEVEL_COUNTRY = 0;
    public static final int LEVEL_ADMIN1 = 1;
    public static final int LEVEL_PLACE = 2;

    public static final int LANG_ZH = 0;
    public static final int LANG_EN = 1;

    private static final byte[] MAGIC = {'W', 'R', 'L', 'D', '1'};
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

        public boolean isEmpty() {
            return xs.length == 0;
        }
    }

    public static final class Feature {
        public final int level;
        public final String nameEn;
        public final String nameZh;
        public final String parent;
        public final double centerLon;
        public final double centerLat;
        public final RingSet detail;
        public final RingSet overview;
        public final float minX;
        public final float minY;
        public final float maxX;
        public final float maxY;
        public final int pop;
        public final int labelRank;
        public final boolean capital;

        Feature(int level, String nameEn, String nameZh, String parent,
                double centerLon, double centerLat,
                RingSet detail, RingSet overview, float minX, float minY,
                float maxX, float maxY, int pop, int labelRank, boolean capital) {
            this.level = level;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.parent = parent;
            this.centerLon = centerLon;
            this.centerLat = centerLat;
            this.detail = detail;
            this.overview = overview;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.pop = pop;
            this.labelRank = labelRank;
            this.capital = capital;
        }

        public boolean hasCenter() {
            return !Double.isNaN(centerLon) && !Double.isNaN(centerLat);
        }

        public boolean hasRings() {
            return detail != null && !detail.isEmpty();
        }

        public String label(int lang) {
            if (lang == LANG_EN) {
                return nameEn == null || nameEn.isEmpty() ? nameZh : nameEn;
            }
            return nameZh == null || nameZh.isEmpty() ? nameEn : nameZh;
        }
    }

    public final Feature[] features;
    public final float minX;
    public final float minY;
    public final float maxX;
    public final float maxY;

    private WorldData(Feature[] features, float minX, float minY,
                      float maxX, float maxY) {
        this.features = features;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public static WorldData load(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("世界地图格式错误：magic 不匹配（需要 WRLD1）");
            }
        }
        int count = in.readInt();
        if (count <= 0 || count > 30000) {
            throw new IOException("世界地图要素数量异常 " + count);
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
            int labelRank = in.readUnsignedByte();
            boolean capital = in.readUnsignedByte() == 1;
            int nameEnLen = in.readUnsignedShort();
            int nameZhLen = in.readUnsignedShort();
            int parentLen = in.readUnsignedShort();
            byte[] en = new byte[nameEnLen];
            byte[] zh = new byte[nameZhLen];
            byte[] par = new byte[parentLen];
            in.readFully(en);
            in.readFully(zh);
            in.readFully(par);
            String nameEn = new String(en, StandardCharsets.UTF_8);
            String nameZh = new String(zh, StandardCharsets.UTF_8);
            String parent = new String(par, StandardCharsets.UTF_8);

            int centerLonUd = in.readInt();
            int centerLatUd = in.readInt();
            double centerLon = centerLonUd == NO_CENTER ? Double.NaN : centerLonUd / 1e6;
            double centerLat = centerLatUd == NO_CENTER ? Double.NaN : centerLatUd / 1e6;
            int bMinLon = in.readInt();
            int bMinLat = in.readInt();
            int bMaxLon = in.readInt();
            int bMaxLat = in.readInt();
            int pop = in.readInt();
            int detailRings = in.readUnsignedShort();
            int overviewRings = in.readUnsignedShort();

            RingSet detail = readRings(in, detailRings);
            RingSet overview = readRings(in, overviewRings);

            float minX = (float) Mercator.lonToX(bMinLon / 1e6);
            float minY = (float) Mercator.latToY(bMaxLat / 1e6);
            float maxX = (float) Mercator.lonToX(bMaxLon / 1e6);
            float maxY = (float) Mercator.latToY(bMinLat / 1e6);
            if (minX < gMinX) gMinX = minX;
            if (minY < gMinY) gMinY = minY;
            if (maxX > gMaxX) gMaxX = maxX;
            if (maxY > gMaxY) gMaxY = maxY;

            features[i] = new Feature(level, nameEn, nameZh, parent, centerLon, centerLat,
                    detail, overview, minX, minY, maxX, maxY, pop, labelRank, capital);
        }
        return new WorldData(features, gMinX, gMinY, gMaxX, gMaxY);
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
                throw new IOException("世界地图环点数异常 " + n);
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

    public static WorldData loadFile(String path) throws IOException {
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

    public int totalPoints(boolean detail) {
        int n = 0;
        for (Feature f : features) {
            RingSet set = detail ? f.detail : f.overview;
            if (set == null) {
                continue;
            }
            for (int l : set.len) {
                n += l;
            }
        }
        return n;
    }
}
