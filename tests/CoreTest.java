import com.btmap.china.map.MapData;
import com.btmap.china.map.Mercator;
import com.btmap.china.track.CoordParser;
import com.btmap.china.track.CoordTransform;
import com.btmap.china.track.TrackStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 纯 JVM 单元测试（不依赖 JUnit / Android）。 */
public final class CoreTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        testMercator();
        testParser();
        testParserChunks();
        testNmea();
        testInvalid();
        testTransform();
        testTrackStore();
        testMapData(root);

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("  FAIL: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private static void near(String name, double actual, double expected, double eps) {
        check(name + " (actual=" + actual + ", expected=" + expected + ")", Math.abs(actual - expected) <= eps);
    }

    private static void testMercator() {
        double lat = 39.9042, lon = 116.4074;
        double x = Mercator.lonToX(lon);
        double y = Mercator.latToY(lat);
        near("投影 x", x, (116.4074 + 180) / 360, 1e-12);
        near("投影往返 lat", Mercator.yToLat(y), lat, 1e-9);
        near("投影往返 lon", Mercator.xToLon(x), lon, 1e-9);
        check("世界坐标范围", x > 0.7 && x < 0.9 && y > 0.2 && y < 0.4);
        double d = Mercator.distanceMeters(39.9042, 116.4074, 31.2304, 121.4737);
        check("北京-上海距离约 1067km (" + Math.round(d / 1000) + "km)", d > 1000_000 && d < 1150_000);
    }

    private static void testParser() {
        CoordParser.Point p = CoordParser.parseLine("31.2304,121.4737");
        check("普通 lat,lon", p != null && Math.abs(p.lat - 31.2304) < 1e-9 && Math.abs(p.lon - 121.4737) < 1e-9);

        p = CoordParser.parseLine("121.4737,31.2304");
        check("lon,lat 自动纠正", p != null && Math.abs(p.lat - 31.2304) < 1e-9 && Math.abs(p.lon - 121.4737) < 1e-9);

        p = CoordParser.parseLine("87.6168,43.8256");
        check("乌鲁木齐 lon>73 时正确纠序", p != null && Math.abs(p.lat - 43.8256) < 1e-9 && Math.abs(p.lon - 87.6168) < 1e-9);

        p = CoordParser.parseLine("31.2304 121.4737 12.5");
        check("空格分隔 + 高度", p != null && Math.abs(p.lat - 31.2304) < 1e-9
                && Math.abs(p.lon - 121.4737) < 1e-9 && p.hasAlt && Math.abs(p.alt - 12.5) < 1e-9);

        p = CoordParser.parseLine("lat: 39.9042 lon: 116.4074");
        check("带键名文本", p != null && Math.abs(p.lat - 39.9042) < 1e-9 && Math.abs(p.lon - 116.4074) < 1e-9);

        p = CoordParser.parseLine("{\"lat\":31.2304,\"lng\":121.4737,\"alt\":8.5}");
        check("JSON", p != null && Math.abs(p.lat - 31.2304) < 1e-9
                && Math.abs(p.lon - 121.4737) < 1e-9 && p.hasAlt && Math.abs(p.alt - 8.5) < 1e-9);

        p = CoordParser.parseLine("31.2304N,121.4737E");
        check("N/E 半球", p != null && Math.abs(p.lat - 31.2304) < 1e-9 && Math.abs(p.lon - 121.4737) < 1e-9);

        p = CoordParser.parseLine("-33.8688,151.2093");
        check("南半球", p != null && Math.abs(p.lat + 33.8688) < 1e-9 && Math.abs(p.lon - 151.2093) < 1e-9);
    }

    private static void testParserChunks() {
        CoordParser parser = new CoordParser();
        final List<double[]> points = new ArrayList<>();
        CoordParser.Listener listener = new CoordParser.Listener() {
            @Override
            public void onPoint(double lat, double lon, double alt, boolean hasAlt, String raw) {
                points.add(new double[]{lat, lon});
            }

            @Override
            public void onInvalid(String raw) {
            }
        };
        byte[] data = "31.2304,121.4737\n39.9042,116.4074\n".getBytes(StandardCharsets.UTF_8);
        for (byte b : data) {
            parser.feed(new byte[]{b}, 1, listener);
        }
        check("逐字节分包解析出 2 点", points.size() == 2);
        if (points.size() == 2) {
            near("分包第 2 点纬度", points.get(1)[0], 39.9042, 1e-9);
        }
        parser.reset();
        int invalidBefore = (int) parser.invalidCount();
        parser.feed("garbage line\n".getBytes(StandardCharsets.UTF_8),
                "garbage line\n".length(), listener);
        check("非法行计数", parser.invalidCount() == invalidBefore + 1);
    }

    private static void testNmea() {
        CoordParser.Point p = CoordParser.parseLine(
                "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47");
        check("NMEA GGA 解析", p != null);
        if (p != null) {
            near("NMEA 纬度", p.lat, 48 + 7.038 / 60, 1e-9);
            near("NMEA 经度", p.lon, 11 + 31.0 / 60, 1e-9);
            near("NMEA 高度", p.alt, 545.4, 1e-9);
        }
        p = CoordParser.parseLine("$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A");
        check("NMEA RMC 解析", p != null && Math.abs(p.lat - 48.1173) < 1e-6);
        p = CoordParser.parseLine("$GPGGA,123519,4807.038,N,01131.000,E,0,00,,,M,,M,,*47");
        check("GGA 无定位不采信", p == null);
    }

    private static void testInvalid() {
        check("乱码", CoordParser.parseLine("hello world") == null);
        check("超出范围", CoordParser.parseLine("95,200") == null);
        check("单个数", CoordParser.parseLine("31.23") == null);
        check("零点占位", CoordParser.parseLine("0,0") == null);
        check("空行", CoordParser.parseLine("  ") == null);
    }

    private static void testTransform() {
        double[] gcj = CoordTransform.wgs84ToGcj02(39.9042, 116.4074);
        check("GCJ 偏移量级合理 (" + (gcj[0] - 39.9042) * 111000 + "m)",
                Math.abs(gcj[0] - 39.9042) > 1e-4 && Math.abs(gcj[0] - 39.9042) < 0.01);
        double[] back = CoordTransform.gcj02ToWgs84(gcj[0], gcj[1]);
        near("GCJ 往返 lat", back[0], 39.9042, 1e-6);
        near("GCJ 往返 lon", back[1], 116.4074, 1e-6);
        double[] outside = CoordTransform.wgs84ToGcj02(48.8566, 2.3522);
        near("境外不偏移", outside[0], 48.8566, 1e-12);
    }

    private static void testTrackStore() {
        TrackStore store = TrackStore.INSTANCE;
        store.clear();
        check("空轨迹", store.size() == 0);
        check("加入第 1 点", store.add(39.9042, 116.4074, 0, false));
        check("过近的点去重", !store.add(39.9042, 116.4074, 0, false));
        check("加入第 2 点", store.add(39.9050, 116.4080, 12.5, true));
        check("点数=2", store.size() == 2);
        check("高度保存", store.hasAlt(1) && Math.abs(store.alt(1) - 12.5) < 1e-9);
        double[] last = store.lastLatLon();
        check("最后一点", last != null && Math.abs(last[0] - 39.9050) < 1e-9);
        store.clear();
        check("清空后 dropped=0", store.droppedCount() == 0 && store.size() == 0);
    }

    private static void testMapData(String root) throws Exception {
        MapData data = MapData.loadFile(root + "/app/src/main/assets/china_map.bin");
        check("要素数量 > 400 (" + data.features.length + ")", data.features.length > 400);
        check("省级 34 个 (" + data.countLevel(MapData.LEVEL_PROVINCE) + ")",
                data.countLevel(MapData.LEVEL_PROVINCE) == 34);
        check("地级市 > 300 (" + data.countLevel(MapData.LEVEL_CITY) + ")",
                data.countLevel(MapData.LEVEL_CITY) > 300);
        check("九段线存在", data.countLevel(MapData.LEVEL_NINEDASH) == 1);
        double bx = Mercator.lonToX(116.4074);
        double by = Mercator.latToY(39.9042);
        check("北京落在数据范围内", bx > data.minX && bx < data.maxX && by > data.minY && by < data.maxY);
        MapData.Feature beijing = null;
        for (MapData.Feature f : data.features) {
            if (f.level == MapData.LEVEL_PROVINCE && f.name.equals("北京市")) {
                beijing = f;
            }
        }
        check("找到北京市", beijing != null);
        if (beijing != null) {
            check("北京市有中心点", beijing.hasCenter());
            check("北京市有环", beijing.ringStart.length > 0 && beijing.ringLen[0] > 3);
        }
    }
}
