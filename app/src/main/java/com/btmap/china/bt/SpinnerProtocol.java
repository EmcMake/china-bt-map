package com.btmap.china.bt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝牙版指尖陀螺“用户模式（红灯）”协议。
 *
 * <p>协议来自《蓝牙版指尖陀螺协议(用户模式).docx》：文字使用 GB2312，
 * 每幅最多 20 个字，指令/响应均以 CRLF 结尾：</p>
 *
 * <pre>
 * T01=1234567\r\n  ->  T01 OK\r\n
 * T02=实例实例实例\r\n -> T02 OK\r\n
 * DEL ALL\r\n     ->  DEL OK\r\n
 * </pre>
 */
public final class SpinnerProtocol {
    public static final Charset GB2312 = Charset.forName("GB2312");
    public static final int MAX_FRAMES = 20;
    public static final int MAX_CHARS = 20;

    private static final Pattern FRAME_OK =
            Pattern.compile("^T(\\d{1,2})\\s+OK$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEL_OK =
            Pattern.compile("^DEL\\s+OK$", Pattern.CASE_INSENSITIVE);

    private SpinnerProtocol() {
    }

    /** 生成第 index 幅文字（1..20）的 GB2312 指令。 */
    public static byte[] encodeFrame(int index, String text) {
        if (index < 1 || index > MAX_FRAMES) {
            throw new IllegalArgumentException("幅号必须是 1-" + MAX_FRAMES);
        }
        String s = text == null ? "" : text;
        if (s.length() > MAX_CHARS) {
            throw new IllegalArgumentException("每幅最多 " + MAX_CHARS + " 个字");
        }
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("文字里不能有换行");
        }
        if (!GB2312.newEncoder().canEncode(s)) {
            throw new IllegalArgumentException("文字包含 GB2312 不支持的字符");
        }
        String cmd = String.format(Locale.US, "T%02d=%s\r\n", index, s);
        return cmd.getBytes(GB2312);
    }

    /** 清除用户模式全部下载文字。 */
    public static byte[] encodeDelAll() {
        return "DEL ALL\r\n".getBytes(GB2312);
    }

    public static String decode(byte[] data) {
        return data == null ? "" : new String(data, GB2312);
    }

    public static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 解析一行响应；未知/错误行 ok=false，但仍保留文本供界面显示。 */
    public static Reply parseLine(String line) {
        String s = line == null ? "" : line.trim();
        Matcher fm = FRAME_OK.matcher(s);
        if (fm.matches()) {
            return new Reply(s, true, Integer.parseInt(fm.group(1)));
        }
        if (DEL_OK.matcher(s).matches()) {
            return new Reply(s, true, 0);
        }
        return new Reply(s, false, 0);
    }

    public static final class Reply {
        public final String text;
        public final boolean ok;
        public final int frame; // 0 表示 DEL ALL / 非帧响应

        Reply(String text, boolean ok, int frame) {
            this.text = text;
            this.ok = ok;
            this.frame = frame;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** 处理 Notify/Read 的半包、粘包，按 CRLF 切分。 */
    public static final class Parser {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public List<Reply> feed(byte[] data) {
            List<Reply> out = new ArrayList<>();
            if (data != null && data.length > 0) {
                buffer.write(data, 0, data.length);
            }
            byte[] all = buffer.toByteArray();
            int start = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == '\n') {
                    int end = i;
                    if (end > start && all[end - 1] == '\r') {
                        end--;
                    }
                    String line = new String(all, start, end - start, GB2312);
                    if (!line.trim().isEmpty()) {
                        out.add(parseLine(line));
                    }
                    start = i + 1;
                }
            }
            buffer.reset();
            if (start < all.length) {
                buffer.write(all, start, all.length - start);
            }
            return out;
        }

        /** 超时时把没有 CRLF 的残留内容也交出来。 */
        public List<Reply> flush() {
            List<Reply> out = new ArrayList<>();
            byte[] all = buffer.toByteArray();
            buffer.reset();
            if (all.length > 0) {
                String line = new String(all, GB2312).trim();
                if (!line.isEmpty()) {
                    out.add(parseLine(line));
                }
            }
            return out;
        }

        public void reset() {
            buffer.reset();
        }
    }
}
