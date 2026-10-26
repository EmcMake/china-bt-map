package com.btmap.china.track;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 当前经纬度的自定义 BLE 发送协议。
 *
 * <p>模板占位符：{lat} {lon} {lat6} {lon6} {acc} {alt} {src} {provider}
 * {datum} {time} {date}；校验占位符：{xor8} {sum8} {crc16}（CRC16-Modbus，小端）。
 * 校验范围是该占位符之前已经生成的字节。支持 \r \n \t \\ \xNN 转义。</p>
 */
public final class LocationProtocol {
    public static final int ENC_UTF8 = 0;
    public static final int ENC_GB2312 = 1;

    public static final int CK_NONE = 0;
    public static final int CK_XOR8 = 1;
    public static final int CK_SUM8 = 2;
    public static final int CK_CRC16 = 3;

    public static final String DEFAULT_TEMPLATE = "{lat},{lon}";
    public static final int MAX_BYTES = 512;

    private final String template;
    private final int encoding;
    private final int precision;
    private final int checksum;
    private final boolean crlf;

    public LocationProtocol(String template, int encoding, int precision,
                            int checksum, boolean crlf) {
        this.template = template == null ? "" : template;
        this.encoding = encoding;
        this.precision = Math.max(0, Math.min(8, precision));
        this.checksum = checksum;
        this.crlf = crlf;
    }

    public String template() {
        return template;
    }

    public int encoding() {
        return encoding;
    }

    public int precision() {
        return precision;
    }

    public int checksum() {
        return checksum;
    }

    public boolean crlf() {
        return crlf;
    }

    public Charset charset() {
        return encoding == ENC_GB2312 ? Charset.forName("GB2312")
                : StandardCharsets.UTF_8;
    }

    public boolean isBinary() {
        return checksum != CK_NONE
                || template.contains("\\x")
                || template.contains("{xor8}")
                || template.contains("{sum8}")
                || template.contains("{crc16}");
    }

    public static final class Values {
        public final double lat;
        public final double lon;
        public final double accuracy;
        public final double altitude;
        public final String source;
        public final String provider;
        public final String datum;
        public final long time;

        public Values(double lat, double lon, double accuracy, double altitude,
                      String source, String provider, String datum, long time) {
            this.lat = lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.altitude = altitude;
            this.source = source == null ? "" : source;
            this.provider = provider == null ? "" : provider;
            this.datum = datum == null ? "" : datum;
            this.time = time;
        }
    }

    public byte[] render(Values v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean tokenChecksum = false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= template.length()) {
                    throw new IllegalArgumentException("模板末尾的 \\ 不完整");
                }
                char e = template.charAt(i);
                switch (e) {
                    case 'r':
                        out.write(0x0D);
                        i++;
                        break;
                    case 'n':
                        out.write(0x0A);
                        i++;
                        break;
                    case 't':
                        out.write(0x09);
                        i++;
                        break;
                    case '\\':
                        out.write('\\');
                        i++;
                        break;
                    case 'x':
                        if (i + 2 >= template.length()) {
                            throw new IllegalArgumentException("\\x 后需要两位十六进制");
                        }
                        int hi = Character.digit(template.charAt(i + 1), 16);
                        int lo = Character.digit(template.charAt(i + 2), 16);
                        if (hi < 0 || lo < 0) {
                            throw new IllegalArgumentException("\\x 后需要两位十六进制");
                        }
                        out.write((hi << 4) | lo);
                        i += 3;
                        break;
                    default:
                        throw new IllegalArgumentException("不支持的转义 \\" + e);
                }
                continue;
            }
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("占位符缺少 }");
                }
                String token = template.substring(i + 1, end);
                i = end + 1;
                switch (token) {
                    case "xor8":
                        appendChecksum(out, CK_XOR8);
                        tokenChecksum = true;
                        break;
                    case "sum8":
                        appendChecksum(out, CK_SUM8);
                        tokenChecksum = true;
                        break;
                    case "crc16":
                        appendChecksum(out, CK_CRC16);
                        tokenChecksum = true;
                        break;
                    default:
                        byte[] piece = value(token, v).getBytes(charset());
                        out.write(piece, 0, piece.length);
                        break;
                }
                if (out.size() > MAX_BYTES) {
                    throw new IllegalArgumentException("协议超过 " + MAX_BYTES + " 字节");
                }
                continue;
            }
            // 普通连续文本，按模板编码一次性写入
            int start = i;
            while (i < template.length()
                    && template.charAt(i) != '\\' && template.charAt(i) != '{') {
                i++;
            }
            String text = template.substring(start, i);
            byte[] piece = text.getBytes(charset());
            out.write(piece, 0, piece.length);
            if (out.size() > MAX_BYTES) {
                throw new IllegalArgumentException("协议超过 " + MAX_BYTES + " 字节");
            }
        }

        if (checksum != CK_NONE && !tokenChecksum) {
            appendChecksum(out, checksum);
        }
        if (crlf) {
            byte[] all = out.toByteArray();
            if (all.length < 2 || all[all.length - 2] != 0x0D || all[all.length - 1] != 0x0A) {
                out.write(0x0D);
                out.write(0x0A);
            }
        }
        if (out.size() > MAX_BYTES) {
            throw new IllegalArgumentException("协议超过 " + MAX_BYTES + " 字节");
        }
        return out.toByteArray();
    }

    public String text(Values v) {
        return new String(render(v), charset());
    }

    public String hex(Values v) {
        byte[] data = render(v);
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private String value(String token, Values v) {
        switch (token) {
            case "lat":
                return String.format(Locale.US, "%." + precision + "f", v.lat);
            case "lon":
                return String.format(Locale.US, "%." + precision + "f", v.lon);
            case "lat6":
                return String.format(Locale.US, "%.6f", v.lat);
            case "lon6":
                return String.format(Locale.US, "%.6f", v.lon);
            case "acc":
                return String.format(Locale.US, "%.0f", v.accuracy);
            case "alt":
                return String.format(Locale.US, "%.1f", v.altitude);
            case "src":
                return v.source;
            case "provider":
                return v.provider;
            case "datum":
                return v.datum;
            case "time":
                return new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(v.time));
            case "date":
                return new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(new Date(v.time));
            default:
                throw new IllegalArgumentException("未知占位符 {" + token + "}");
        }
    }

    private static void appendChecksum(ByteArrayOutputStream out, int type) {
        byte[] data = out.toByteArray();
        if (type == CK_XOR8) {
            int b = 0;
            for (byte x : data) {
                b ^= x & 0xFF;
            }
            out.write(b);
        } else if (type == CK_SUM8) {
            int b = 0;
            for (byte x : data) {
                b = (b + (x & 0xFF)) & 0xFF;
            }
            out.write(b);
        } else if (type == CK_CRC16) {
            int crc = 0xFFFF;
            for (byte x : data) {
                crc ^= x & 0xFF;
                for (int k = 0; k < 8; k++) {
                    if ((crc & 1) != 0) {
                        crc = (crc >>> 1) ^ 0xA001;
                    } else {
                        crc >>>= 1;
                    }
                }
            }
            out.write(crc & 0xFF);
            out.write((crc >>> 8) & 0xFF);
        }
    }
}
