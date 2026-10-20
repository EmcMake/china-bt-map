package com.btmap.china.map;

/** Web 墨卡托投影工具。世界坐标 x/y 均归一化到 [0, 1]，便于统一缩放。 */
public final class Mercator {
    public static final double EARTH_CIRCUMFERENCE = 40075016.686;

    private Mercator() {
    }

    public static double lonToX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    public static double latToY(double lat) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double rad = Math.toRadians(clamped);
        return 0.5 - Math.log(Math.tan(Math.PI / 4.0 + rad / 2.0)) / (2.0 * Math.PI);
    }

    public static double xToLon(double x) {
        return x * 360.0 - 180.0;
    }

    public static double yToLat(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y))));
    }

    /** 某纬度上一整圈（世界宽度 1.0）对应的地面米数。 */
    public static double metersPerWorldUnit(double lat) {
        return EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians(lat));
    }

    /** 半正矢公式，单位米。 */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
