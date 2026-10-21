package com.btmap.china.map;

/** 瓦片编号与缩放级别换算（纯 Java，可单测）。 */
public final class TileMath {
    private TileMath() {
    }

    /** 当前 scale（世界宽度对应的像素数）对应的瓦片层级。 */
    public static int zoomForScale(double scale, int minZoom, int maxZoom) {
        int z = (int) Math.round(Math.log(scale / 256.0) / Math.log(2));
        return Math.max(minZoom, Math.min(maxZoom, z));
    }

    /** 世界坐标 [0,1] 在层级 z 的瓦片列/行。 */
    public static int tileIndex(double world, int z) {
        int n = 1 << Math.min(30, z);
        int i = (int) Math.floor(world * n);
        if (i < 0) {
            return 0;
        }
        if (i >= n) {
            return n - 1;
        }
        return i;
    }

    public static int tileCount(int z) {
        return 1 << Math.min(30, z);
    }

    /** 层级 z 下单块瓦片占的世界坐标宽度。 */
    public static double tileWorldSize(int z) {
        return 1.0 / tileCount(z);
    }
}
