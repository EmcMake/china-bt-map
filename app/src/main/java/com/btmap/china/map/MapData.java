package com.btmap.china.map;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置地图数据（assets/china_map.bin）的读取器。
 *
 * <p>文件格式见 tools/pack_map.py。数据已经在加载时投影为世界墨卡托归一化坐标，
 * 绘制时只需乘缩放矩阵。</p>
 */
public final class MapData {
    public static final int LEVEL_PROVINCE = 0;
    public static final int LEVEL_CITY = 1;
    public static final int LEVEL_NINEDASH = 2;

    private static final byte[] MAGIC = {'C', 'N', 'M', 'A', 'P', '1'};

    public static final class Feature {
        public final int level;
        public final String name;
        /** 标注点（经度/纬度），没有标注点时是 NaN。 */
        public final float centerLon;
        public final float centerLat;
        /** 所有环的坐标扁平存放（世界墨卡托）。 */
        public final float[] xs;
        public final float[] ys;
        public final int[] ringStart;
        public final int[] ringLen;

        Feature(int level, String name, float centerLon, float centerLat,
                float[] xs, float[] ys, int[] ringStart, int[] ringLen) {
            this.level = level;
            this.name = name;
            this.centerLon = centerLon;
            this.centerLat = centerLat;
            this.xs = xs;
            this.ys = ys;
            this.ringStart = ringStart;
            this.ringLen = ringLen;
        }

        public boolean hasCenter() {
            return !Float.isNaN(centerLon) && !Float.isNaN(centerLat);
        }
    }

    public final Feature[] features;
    /** 数据整体范围（世界墨卡托归一化）。 */
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
                throw new IOException("地图数据格式错误：magic 不匹配");
            }
        }
        int count = in.readInt();
        if (count <= 0 || count > 10000) {
            throw new IOException("地图数据格式错误：要素数量异常 " + count);
        }
        in.readFloat(); // min lon（备用）
        in.readFloat(); // min lat
        in.readFloat(); // max lon
        in.readFloat(); // max lat

        Feature[] features = new Feature[count];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            int level = in.readUnsignedByte();
            in.readUnsignedByte(); // flags，保留
            float centerLon = in.readFloat();
            float centerLat = in.readFloat();
            int nameLen = in.readUnsignedShort();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int ringCount = in.readUnsignedShort();

            int[] ringStart = new int[ringCount];
            int[] ringLen = new int[ringCount];
            int total = 0;
            float[] xs = new float[1024];
            float[] ys = new float[1024];
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
                ringStart[r] = total;
                ringLen[r] = n;
                for (int k = 0; k < n; k++) {
                    float lon = in.readFloat();
                    float lat = in.readFloat();
                    float x = (float) Mercator.lonToX(lon);
                    float y = (float) Mercator.latToY(lat);
                    xs[total] = x;
                    ys[total] = y;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    total++;
                }
            }
            features[i] = new Feature(level, name, centerLon, centerLat, xs, ys, ringStart, ringLen);
            features[i] = new Feature(level, name, centerLon, centerLat, xs, ys, ringStart, ringLen);
        }
        return new MapData(features, minX, minY, maxX, maxY);
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
