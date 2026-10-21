import com.btmap.china.map.MapData;
import com.btmap.china.map.TileMath;
import com.btmap.china.map.TileSource;
import com.btmap.china.map.Mercator;
import com.btmap.china.track.CoordParser;
import com.btmap.china.track.CoordSystems;
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
        testCoordSystems();
        testBd09();
        testAccuracy();
        testTrackStore();
        testRedatum();
        testZoomPrecision();
        testMapData(root);
        testTileMath();
        testWgsConversion();

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
            public void onPoint(double lat, double lon, double alt, boolean hasAlt,
                                double accuracy, String raw) {
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

    private static void testCoordSystems() {
        check("默认坐标系是 GCJ-02", CoordSystems.DEFAULT == CoordSystems.GCJ02);
        double[] gcj = CoordSystems.toMap(CoordSystems.GCJ02, 39.9042, 116.4074);
        near("GCJ 原样 lat", gcj[0], 39.9042, 1e-12);
        near("GCJ 原样 lon", gcj[1], 116.4074, 1e-12);

        double[] wgs = CoordSystems.toMap(CoordSystems.WGS84, 39.9042, 116.4074);
        double[] old = CoordTransform.wgs84ToGcj02(39.9042, 116.4074);
        near("WGS 映射与旧接口一致 lat", wgs[0], old[0], 1e-12);
        near("WGS 映射与旧接口一致 lon", wgs[1], old[1], 1e-12);

        double[] cgcs = CoordSystems.toMap(CoordSystems.CGCS2000, 39.9042, 116.4074);
        near("CGCS2000 按 WGS 处理", cgcs[0], old[0], 1e-12);

        double[] out = CoordSystems.toMap(CoordSystems.WGS84, 48.8566, 2.3522);
        near("境外 WGS 不偏移", out[0], 48.8566, 1e-12);
        check("旧设置迁移 0→GCJ", CoordSystems.fromLegacy(0) == CoordSystems.GCJ02);
        check("旧设置迁移 1→WGS", CoordSystems.fromLegacy(1) == CoordSystems.WGS84);
    }

    private static void testBd09() {
        double lat = 39.9087, lon = 116.3975;      // GCJ 天安门附近
        double[] bd = CoordTransform.gcj02ToBd09(lat, lon);
        double d = Mercator.distanceMeters(lat, lon, bd[0], bd[1]);
        check("BD-09 偏移量级 (" + Math.round(d) + "m)", d > 100 && d < 1200);
        double[] back = CoordTransform.bd09ToGcj02(bd[0], bd[1]);
        near("BD-09 往返 lat", back[0], lat, 1e-6);
        near("BD-09 往返 lon", back[1], lon, 1e-6);
        double[] outside = CoordTransform.bd09ToGcj02(48.8566, 2.3522);
        near("境外 BD-09 不偏移", outside[0], 48.8566, 1e-12);
    }

    private static void testAccuracy() {
        CoordParser.Point p = CoordParser.parseLine(
                "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47");
        check("GGA HDOP→精度", p != null && Math.abs(p.accuracy - 4.5) < 1e-6);
        p = CoordParser.parseLine("$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A");
        check("RMC 精度未知为 0", p != null && p.accuracy == 0);
        p = CoordParser.parseLine("31.2304,121.4737");
        check("文本精度未知为 0", p != null && p.accuracy == 0);
    }

    private static void testRedatum() {
        TrackStore store = TrackStore.INSTANCE;
        store.clear();
        store.add(39.9042, 116.4074, 0, false, 0, CoordSystems.WGS84);
        double mapBefore = store.mapLat(0);
        check("WGS 原始点底图坐标已偏移", Math.abs(mapBefore - 39.9042) > 1e-5);
        near("原始坐标保留", store.rawLat(0), 39.9042, 1e-12);
        store.redatumAll(CoordSystems.GCJ02);
        near("重算后底图坐标等于原始", store.mapLat(0), 39.9042, 1e-12);
        check("重整 epoch 递增", store.datumEpoch() > 0);
        check("datum 更新", store.datum(0) == CoordSystems.GCJ02);
        store.clear();
    }

    private static void testZoomPrecision() {
        // 模拟最大缩放（约 10m/px）下，float 世界坐标的误差应远小于 1px
        double scale = 4_000_000;
        double wx = Mercator.lonToX(116.4074);
        double wy = Mercator.latToY(39.9042);
        float fx = (float) wx;
        float fy = (float) wy;
        double exPx = Math.abs(fx - wx) * scale;
        double eyPx = Math.abs(fy - wy) * scale;
        check(String.format("最大缩放路径误差 (%.3fpx, %.3fpx)", exPx, eyPx),
                exPx < 0.5 && eyPx < 0.5);
        double latBack = Mercator.yToLat(Mercator.latToY(39.9042));
        near("投影往返精度", latBack, 39.9042, 1e-9);
    }

    private static void testTrackStore() {
        TrackStore store = TrackStore.INSTANCE;
        store.clear();
        check("空轨迹", store.size() == 0);
        check("加入第 1 点", store.add(39.9042, 116.4074, 0, false, 0, CoordSystems.GCJ02));
        check("过近的点去重", !store.add(39.9042, 116.4074, 0, false, 0, CoordSystems.GCJ02));
        check("加入第 2 点", store.add(39.9050, 116.4080, 12.5, true, 8, CoordSystems.GCJ02));
        check("点数=2", store.size() == 2);
        check("高度保存", store.hasAlt(1) && Math.abs(store.alt(1) - 12.5) < 1e-9);
        check("精度保存", Math.abs(store.accuracy(1) - 8) < 1e-6);
        check("坐标系记录", store.datum(1) == CoordSystems.GCJ02);
        double[] last = store.lastLatLon();
        check("最后一点", last != null && Math.abs(last[0] - 39.9050) < 1e-9);
        store.clear();
        check("清空后 dropped=0", store.droppedCount() == 0 && store.size() == 0);
    }

    private static void testTileMath() {
        check("z17 层级", TileMath.zoomForScale(256.0 * (1 << 17), 0, 19) == 17);
        check("层级上限", TileMath.zoomForScale(1e12, 0, 19) == 19);
        check("层级下限", TileMath.zoomForScale(1, 3, 19) == 3);
        check("瓦片编号边界", TileMath.tileIndex(1.0, 3) == 7
                && TileMath.tileIndex(-0.5, 3) == 0);
        check("瓦片数量", TileMath.tileCount(5) == 32);

        TileSource amap = TileSource.forRaster(TileSource.TYPE_STREET,
                TileSource.PROVIDER_AMAP, "");
        check("高德街道 GCJ-02", amap.datum == TileSource.DATUM_GCJ02);
        check("高德街道高缩放", amap.maxZoom >= 17);
        check("高德 URL", amap.url(10, 3, 4, "").contains("x=3")
                && amap.url(10, 3, 4, "").contains("y=4"));

        TileSource tdt = TileSource.forRaster(TileSource.TYPE_SATELLITE,
                TileSource.PROVIDER_TIANDITU, "KEY123");
        check("天地图 WGS-84", tdt.datum == TileSource.DATUM_WGS84);
        check("天地图 tk", tdt.url(5, 1, 2, "KEY123").contains("tk=KEY123"));
        TileSource fallback = TileSource.forRaster(TileSource.TYPE_STREET,
                TileSource.PROVIDER_TIANDITU, "");
        check("天地图无 tk 回退高德", fallback.provider == TileSource.PROVIDER_AMAP);
    }

    private static void testWgsConversion() {
        double[] w = CoordSystems.toWgs84(CoordSystems.GCJ02, 39.9042, 116.4074);
        double d = Mercator.distanceMeters(39.9042, 116.4074, w[0], w[1]);
        check("GCJ->WGS 偏移 100-800m (" + Math.round(d) + "m)", d > 100 && d < 800);
        double[] id = CoordSystems.toWgs84(CoordSystems.WGS84, 39.9042, 116.4074);
        check("WGS->WGS 不变", Math.abs(id[0] - 39.9042) < 1e-9
                && Math.abs(id[1] - 116.4074) < 1e-9);
        double[] bd = CoordSystems.toWgs84(CoordSystems.BD09, 39.9042, 116.4074);
        double db = Mercator.distanceMeters(39.9042, 116.4074, bd[0], bd[1]);
        check("BD->WGS 有偏移 (" + Math.round(db) + "m)", db > 50);
    }

    private static void testMapData(String root) throws Exception {
        MapData data = MapData.loadFile(root + "/app/src/main/assets/china_map.bin");
        check("要素数量 > 400 (" + data.features.length + ")", data.features.length > 400);
        check("省级 34 个 (" + data.countLevel(MapData.LEVEL_PROVINCE) + ")",
                data.countLevel(MapData.LEVEL_PROVINCE) == 34);
        check("地级市 > 300 (" + data.countLevel(MapData.LEVEL_CITY) + ")",
                data.countLevel(MapData.LEVEL_CITY) > 300);
        check("九段线存在", data.countLevel(MapData.LEVEL_NINEDASH) == 1);
        check("区县 > 2500 (" + data.countLevel(MapData.LEVEL_COUNTY) + ")",
                data.countLevel(MapData.LEVEL_COUNTY) > 2500);
        int countyPts = 0;
        for (MapData.Feature f : data.features) {
            if (f.level == MapData.LEVEL_COUNTY) {
                for (int len : f.detail.len) {
                    countyPts += len;
                }
            }
        }
        check("区县 detail > 80 万点 (" + countyPts + ")", countyPts > 800000);
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
            check("北京市有 detail 环", beijing.detail.start.length > 0 && beijing.detail.len[0] > 3);
            check("北京市有 overview 环", beijing.overview.start.length > 0);
            check("detail 点数 >= overview",
                    beijing.detail.xs.length >= beijing.overview.xs.length);
        }
    }
}
