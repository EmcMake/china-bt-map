package com.btmap.china.track;

/** WGS-84 ↔ GCJ-02（火星坐标）转换，用于对齐国内地图数据与 GPS 原始坐标。 */
public final class CoordTransform {
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private CoordTransform() {
    }

    public static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    /** 返回 {lat, lon}。 */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** 返回 {lat, lon}，迭代逼近，误差 < 1e-7 度。 */
    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double wgsLat = lat, wgsLon = lon;
        for (int i = 0; i < 6; i++) {
            double[] g = wgs84ToGcj02(wgsLat, wgsLon);
            wgsLat += lat - g[0];
            wgsLon += lon - g[1];
        }
        return new double[]{wgsLat, wgsLon};
    }

    private static final double X_PI = Math.PI * 3000.0 / 180.0;

    /** BD-09 → GCJ-02。境外原样返回。 */
    public static double[] bd09ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double x = lon - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    /** GCJ-02 → BD-09。境外原样返回。 */
    public static double[] gcj02ToBd09(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double z = Math.sqrt(lon * lon + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lon) + 0.000003 * Math.cos(lon * X_PI);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
