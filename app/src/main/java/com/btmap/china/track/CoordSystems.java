package com.btmap.china.track;

/** 输入坐标系的定义与到底图（GCJ-02）的换算。 */
public final class CoordSystems {
    public static final int WGS84 = 0;
    public static final int GCJ02 = 1;
    public static final int BD09 = 2;
    public static final int CGCS2000 = 3;

    /** 默认：与内置底图一致。 */
    public static final int DEFAULT = GCJ02;

    private CoordSystems() {
    }

    public static String name(int datum) {
        switch (datum) {
            case WGS84:
                return "WGS-84";
            case BD09:
                return "BD-09";
            case CGCS2000:
                return "CGCS2000";
            default:
                return "GCJ-02";
        }
    }

    public static String description(int datum) {
        switch (datum) {
            case WGS84:
                return "GPS 芯片原始输出，国际通用；需要换算到国内底图";
            case BD09:
                return "百度地图私有坐标，在 GCJ-02 上二次加密";
            case CGCS2000:
                return "国家大地坐标系，与 WGS-84 基本一致（差异 <10cm）";
            default:
                return "国内主流地图/高德/腾讯标准，与内置底图一致";
        }
    }

    /** 底部芯片上的简短说明。 */
    public static String chipLabel(int datum) {
        if (datum == GCJ02) {
            return "GCJ-02（底图）";
        }
        return name(datum) + " → GCJ-02";
    }

    public static boolean needsConversion(int datum) {
        return datum == WGS84 || datum == BD09 || datum == CGCS2000;
    }

    /** 返回底图坐标系（GCJ-02）下的 {lat, lon}。 */
    public static double[] toMap(int datum, double lat, double lon) {
        switch (datum) {
            case WGS84:
            case CGCS2000:
                return CoordTransform.wgs84ToGcj02(lat, lon);
            case BD09:
                return CoordTransform.bd09ToGcj02(lat, lon);
            default:
                return new double[]{lat, lon};
        }
    }

    public static int fromLegacy(int coordMode) {
        // 旧版：0=不转换 1=WGS84→GCJ02 2=GCJ02→WGS84
        if (coordMode == 1) {
            return WGS84;
        }
        return GCJ02;
    }
}
