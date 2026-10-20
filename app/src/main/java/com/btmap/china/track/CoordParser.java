package com.btmap.china.track;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝牙坐标行解析器（纯 Java，无 Android 依赖，可单元测试）。
 *
 * <p>支持：</p>
 * <pre>
 *   31.2304,121.4737
 *   31.2304 121.4737 12.5
 *   lat:31.2304 lon:121.4737
 *   31.2304N,121.4737E
 *   {"lat":31.2304,"lng":121.4737,"alt":12.5}
 *   $GPGGA,123519,3113.824,N,12128.422,E,1,08,0.9,545.4,M,46.9,M,,*47
 *   $GPRMC,123519,A,3113.824,N,12128.422,E,...
 * </pre>
 *
 * <p>纬度/经度顺序：带 N/S/E/W 或 JSON 键时按语义取；纯数字时优先按“纬度,经度”，
 * 若该解释不可能（超出 ±90/±180）或明显不像中国范围则自动交换。</p>
 */
public final class CoordParser {
    public interface Listener {
        void onPoint(double lat, double lon, double alt, boolean hasAlt, String rawLine);

        void onInvalid(String rawLine);
    }

    private static final int MAX_LINE = 8192;
    private static final Pattern NUM = Pattern.compile("[-+]?\\d{1,3}(?:\\.\\d+)?");
    private static final Pattern NUM_HEMI =
            Pattern.compile("([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*([NSEWnsew])?");
    private static final Pattern JSON_KEY =
            Pattern.compile("(?i)\"(lat|latitude|lon|lng|longitude|alt|altitude|height)\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)");

    private byte[] pending = new byte[1024];
    private int pendingLen;
    private long invalidCount;

    public long invalidCount() {
        return invalidCount;
    }

    public void reset() {
        pendingLen = 0;
    }

    /** 喂入蓝牙数据块，按行解析后回调。 */
    public void feed(byte[] data, int len, Listener listener) {
        for (int i = 0; i < len; i++) {
            byte b = data[i];
            if (b == '\n' || b == '\r') {
                flush(listener);
            } else {
                if (pendingLen == pending.length) {
                    byte[] bigger = new byte[Math.min(MAX_LINE * 2, pending.length * 2)];
                    System.arraycopy(pending, 0, bigger, 0, pendingLen);
                    pending = bigger;
                }
                pending[pendingLen++] = b;
                if (pendingLen > MAX_LINE) {
                    pendingLen = 0;
                    invalidCount++;
                    if (listener != null) {
                        listener.onInvalid("<行过长，已丢弃>");
                    }
                }
            }
        }
    }

    private void flush(Listener listener) {
        if (pendingLen == 0) {
            return;
        }
        String line = new String(pending, 0, pendingLen, StandardCharsets.UTF_8).trim();
        pendingLen = 0;
        if (line.isEmpty()) {
            return;
        }
        Point p = parseLine(line);
        if (p == null) {
            invalidCount++;
            if (listener != null) {
                listener.onInvalid(line);
            }
        } else if (listener != null) {
            listener.onPoint(p.lat, p.lon, p.alt, p.hasAlt, line);
        }
    }

    /** 直接解析一行（供测试与“输入测试坐标”使用）。 */
    public static Point parseLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return null;
        }
        Point p = null;
        if (s.startsWith("$")) {
            p = parseNmea(s);
        } else if (s.startsWith("{") || s.startsWith("[")) {
            p = parseJson(s);
        }
        if (p == null) {
            p = parsePlain(s);
        }
        if (p == null) {
            return null;
        }
        if (Double.isNaN(p.lat) || Double.isNaN(p.lon)
                || Math.abs(p.lat) > 90.0 || Math.abs(p.lon) > 180.0) {
            return null;
        }
        if (Math.abs(p.lat) < 1e-9 && Math.abs(p.lon) < 1e-9) {
            return null; // (0,0) 通常是设备未定位的占位值
        }
        return p;
    }

    // ---------------------------------------------------------------- JSON
    private static Point parseJson(String s) {
        Double lat = null, lon = null, alt = null;
        Matcher m = JSON_KEY.matcher(s);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            double v;
            try {
                v = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            switch (key) {
                case "lat":
                case "latitude":
                    lat = v;
                    break;
                case "lon":
                case "lng":
                case "longitude":
                    lon = v;
                    break;
                default:
                    alt = v;
                    break;
            }
        }
        if (lat == null || lon == null) {
            return null;
        }
        Point p = new Point(lat, lon);
        if (alt != null) {
            p.alt = alt;
            p.hasAlt = true;
        }
        return p;
    }

    // ---------------------------------------------------------------- NMEA
    private static Point parseNmea(String raw) {
        String s = raw;
        int star = s.indexOf('*');
        if (star > 0) {
            s = s.substring(0, star);
        }
        String[] f = s.split(",", -1);
        if (f.length < 7) {
            return null;
        }
        String type = f[0].toUpperCase();
        try {
            if (type.endsWith("GGA")) {
                if (f.length < 10 || f[6].isEmpty() || "0".equals(f[6])) {
                    return null;
                }
                double lat = nmeaCoord(f[2], f[3]);
                double lon = nmeaCoord(f[4], f[5]);
                Point p = new Point(lat, lon);
                if (!f[9].isEmpty()) {
                    p.alt = Double.parseDouble(f[9]);
                    p.hasAlt = true;
                }
                return p;
            }
            if (type.endsWith("RMC")) {
                if (f.length < 7 || !"A".equalsIgnoreCase(f[2])) {
                    return null;
                }
                return new Point(nmeaCoord(f[3], f[4]), nmeaCoord(f[5], f[6]));
            }
            if (type.endsWith("GLL")) {
                if (f.length < 6) {
                    return null;
                }
                return new Point(nmeaCoord(f[1], f[2]), nmeaCoord(f[3], f[4]));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    /** ddmm.mmmm / dddmm.mmmm → 十进制度。 */
    private static double nmeaCoord(String value, String hemi) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        int dot = value.indexOf('.');
        String whole = dot < 0 ? value : value.substring(0, dot);
        int degDigits = whole.length() - 2;
        if (degDigits <= 0) {
            throw new NumberFormatException("bad nmea: " + value);
        }
        double deg = Double.parseDouble(value.substring(0, degDigits));
        double min = Double.parseDouble(value.substring(degDigits));
        double out = deg + min / 60.0;
        if ("S".equalsIgnoreCase(hemi) || "W".equalsIgnoreCase(hemi)) {
            out = -out;
        }
        return out;
    }

    // ---------------------------------------------------------------- 纯文本
    private static Point parsePlain(String s) {
        List<Double> nums = new ArrayList<>(4);
        boolean hasHemi = false;
        double hemiLat = Double.NaN, hemiLon = Double.NaN;
        Matcher m = NUM_HEMI.matcher(s);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String h = m.group(2);
            nums.add(v);
            if (h != null && !h.isEmpty()) {
                hasHemi = true;
                char c = Character.toUpperCase(h.charAt(0));
                if (c == 'N' || c == 'S') {
                    hemiLat = c == 'S' ? -Math.abs(v) : Math.abs(v);
                } else {
                    hemiLon = c == 'W' ? -Math.abs(v) : Math.abs(v);
                }
            }
        }
        if (hasHemi && !Double.isNaN(hemiLat) && !Double.isNaN(hemiLon)) {
            return new Point(hemiLat, hemiLon);
        }
        if (nums.size() < 2) {
            return null;
        }
        double a = nums.get(0);
        double b = nums.get(1);
        double[] pair = pickOrder(a, b);
        if (pair == null) {
            return null;
        }
        Point p = new Point(pair[0], pair[1]);
        if (nums.size() >= 3) {
            double third = nums.get(2);
            if (Math.abs(third) < 20000) {
                p.alt = third;
                p.hasAlt = true;
            }
        }
        return p;
    }

    /**
     * 决定 (a,b) 中哪个是纬度。返回 {lat, lon}，都非法时返回 null。
     * 中国范围内经度通常 > 73，纬度 ≤ 54，据此消歧。
     */
    static double[] pickOrder(double a, double b) {
        boolean aLat = Math.abs(a) <= 90, aLon = Math.abs(a) <= 180;
        boolean bLat = Math.abs(b) <= 90, bLon = Math.abs(b) <= 180;
        boolean latFirst = aLat && bLon;   // (lat=a, lon=b)
        boolean lonFirst = aLon && bLat;   // (lat=b, lon=a)
        if (!latFirst && !lonFirst) {
            return null;
        }
        if (latFirst && !lonFirst) {
            return new double[]{a, b};
        }
        if (lonFirst && !latFirst) {
            return new double[]{b, a};
        }
        // 两种都合法：用中国范围打分
        int scoreLatFirst = chinaScore(a, b);
        int scoreLonFirst = chinaScore(b, a);
        if (scoreLatFirst > scoreLonFirst) {
            return new double[]{a, b};
        }
        if (scoreLonFirst > scoreLatFirst) {
            return new double[]{b, a};
        }
        return new double[]{a, b}; // 默认 纬度,经度
    }

    private static int chinaScore(double lat, double lon) {
        int score = 0;
        if (lat >= 3 && lat <= 54) score += 2;
        if (lon >= 73 && lon <= 136) score += 4;
        if (Math.abs(lat) > 60) score -= 3;
        return score;
    }

    public static final class Point {
        public final double lat;
        public final double lon;
        public double alt;
        public boolean hasAlt;

        public Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.6f,%.6f%s",
                    lat, lon, hasAlt ? String.format(java.util.Locale.US, ",%.1f", alt) : "");
        }
    }
}
