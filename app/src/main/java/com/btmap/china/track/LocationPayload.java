package com.btmap.china.track;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 当前经纬度 -> BLE 文本载荷。 */
public final class LocationPayload {
    public static final int STYLE_PLAIN = 0;      // 31.230400,121.473700
    public static final int STYLE_LABELED = 1;    // LAT=31.230400,LON=121.473700
    public static final int STYLE_WITH_ACC = 2;  // 31.230400,121.473700,8

    private LocationPayload() {
    }

    public static String format(double lat, double lon, double accuracy, int style) {
        switch (style) {
            case STYLE_LABELED:
                return String.format(Locale.US, "LAT=%.6f,LON=%.6f", lat, lon);
            case STYLE_WITH_ACC:
                return String.format(Locale.US, "%.6f,%.6f,%.0f", lat, lon, accuracy);
            case STYLE_PLAIN:
            default:
                return String.format(Locale.US, "%.6f,%.6f", lat, lon);
        }
    }

    /** 保证末尾是 CRLF；文本里已有则不重复。 */
    public static String ensureCrlf(String text) {
        String s = text == null ? "" : text;
        if (s.endsWith("\r\n")) {
            return s;
        }
        if (s.endsWith("\n")) {
            return s.substring(0, s.length() - 1) + "\r\n";
        }
        return s + "\r\n";
    }

    public static byte[] bytes(String text) {
        return ensureCrlf(text).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] build(double lat, double lon, double accuracy, int style) {
        return bytes(format(lat, lon, accuracy, style));
    }
}
