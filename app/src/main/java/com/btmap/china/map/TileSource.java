package com.btmap.china.map;

import java.util.Locale;

/**
 * 底图类型与瓦片源定义。
 *
 * <p>高德（AMAP）瓦片为 GCJ-02，和内置矢量底图同一基准，道路最全；
 * 天地图/Esri 为 WGS-84（CGCS2000 近似），需要把轨迹重投影。</p>
 */
public final class TileSource {
    public static final int TYPE_VECTOR = 0;     // 离线矢量（GCJ-02）
    public static final int TYPE_STREET = 1;     // 在线街道
    public static final int TYPE_SATELLITE = 2;  // 在线卫星
    public static final int TYPE_TERRAIN = 3;    // 在线地形
    public static final int TYPE_HYBRID = 4;     // 卫星 + 路网/地名

    public static final int PROVIDER_AMAP = 0;
    public static final int PROVIDER_ESRI = 1;
    public static final int PROVIDER_TIANDITU = 2;

    public static final int DATUM_GCJ02 = 0;
    public static final int DATUM_WGS84 = 1;

    public static final String ATTR_VECTOR = "GCJ-02 底图 · DataV.GeoAtlas";
    public static final String ATTR_AMAP = "© 高德地图 · GCJ-02";
    public static final String ATTR_ESRI_IMG =
            "Esri, Maxar, Earthstar Geographics, GIS User Community";
    public static final String ATTR_ESRI_STREET =
            "Esri, HERE, Garmin, USGS, OpenStreetMap contributors";
    public static final String ATTR_ESRI_TOPO =
            "Esri, HERE, Garmin, FAO, NOAA, USGS";
    public static final String ATTR_TIANDITU = "© 天地图";

    public final int type;
    public final int provider;
    public final int datum;      // DATUM_GCJ02 / DATUM_WGS84
    public final String name;
    public final String urlTemplate;
    public final String attribution;
    public final int minZoom;
    public final int maxZoom;
    public final boolean raster;
    public final boolean transparent;

    private TileSource(int type, int provider, int datum, String name, String urlTemplate,
                       String attribution, int minZoom, int maxZoom, boolean raster) {
        this(type, provider, datum, name, urlTemplate, attribution, minZoom, maxZoom,
                raster, false);
    }

    private TileSource(int type, int provider, int datum, String name, String urlTemplate,
                       String attribution, int minZoom, int maxZoom, boolean raster,
                       boolean transparent) {
        this.type = type;
        this.provider = provider;
        this.datum = datum;
        this.name = name;
        this.urlTemplate = urlTemplate;
        this.attribution = attribution;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.raster = raster;
        this.transparent = transparent;
    }

    public static TileSource vector() {
        return new TileSource(TYPE_VECTOR, PROVIDER_AMAP, DATUM_GCJ02, "标准", "",
                ATTR_VECTOR, 0, 22, false);
    }

    public static String typeName(int type) {
        switch (type) {
            case TYPE_STREET:
                return "街道";
            case TYPE_SATELLITE:
                return "卫星";
            case TYPE_TERRAIN:
                return "地形";
            case TYPE_HYBRID:
                return "卫星+路网";
            default:
                return "标准";
        }
    }

    public static String providerName(int provider) {
        switch (provider) {
            case PROVIDER_TIANDITU:
                return "天地图";
            case PROVIDER_ESRI:
                return "Esri";
            default:
                return "高德";
        }
    }

    private static String amapBase(int type) {
        if (type == TYPE_STREET) {
            return "https://webrd0{s}.is.autonavi.com/appmaptile"
                    + "?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}";
        }
        return "https://webst0{s}.is.autonavi.com/appmaptile"
                + "?style=6&x={x}&y={y}&z={z}";
    }

    private static String amapName(int type) {
        switch (type) {
            case TYPE_STREET:
                return "街道（高德）";
            case TYPE_HYBRID:
                return "卫星+路网（高德）";
            default:
                return "卫星（高德）";
        }
    }

    /**
     * @param type     TYPE_STREET / TYPE_SATELLITE / TYPE_HYBRID / TYPE_TERRAIN
     * @param provider PROVIDER_AMAP / PROVIDER_ESRI / PROVIDER_TIANDITU
     * @param key      天地图 tk，可为空
     */
    public static TileSource forRaster(int type, int provider, String key) {
        boolean hasKey = key != null && !key.trim().isEmpty();
        if (provider == PROVIDER_TIANDITU && !hasKey) {
            provider = PROVIDER_AMAP; // 没填 tk 时回退高德
        }

        if (provider == PROVIDER_TIANDITU && hasKey) {
            String layer;
            String name;
            switch (type) {
                case TYPE_STREET:
                    layer = "vec_w";
                    name = "街道（天地图）";
                    break;
                case TYPE_TERRAIN:
                    layer = "ter_w";
                    name = "地形（天地图）";
                    break;
                case TYPE_HYBRID:
                    layer = "img_w";
                    name = "卫星+路网（天地图）";
                    break;
                default:
                    layer = "img_w";
                    name = "卫星（天地图）";
                    break;
            }
            String url = "https://t{s}.tianditu.gov.cn/" + layer
                    + "/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                    + "&LAYER=" + layer.substring(0, 3)
                    + "&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles"
                    + "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={key}";
            return new TileSource(type, PROVIDER_TIANDITU, DATUM_WGS84, name, url,
                    ATTR_TIANDITU, 1, 18, true);
        }

        if (provider == PROVIDER_AMAP && type != TYPE_TERRAIN) {
            return new TileSource(type, PROVIDER_AMAP, DATUM_GCJ02, amapName(type),
                    amapBase(type), ATTR_AMAP, 0, 18, true);
        }

        // Esri / 高德地形回退
        String url;
        String name;
        String attr;
        int maxZ;
        switch (type) {
            case TYPE_STREET:
                url = "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "World_Street_Map/MapServer/tile/{z}/{y}/{x}";
                name = "街道（Esri）";
                attr = ATTR_ESRI_STREET;
                maxZ = 13; // 中国境内 Esri 街道图只到 z13
                break;
            case TYPE_TERRAIN:
                url = "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "World_Topo_Map/MapServer/tile/{z}/{y}/{x}";
                name = "地形（Esri）";
                attr = ATTR_ESRI_TOPO;
                maxZ = 13;
                break;
            case TYPE_HYBRID:
                url = "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "World_Imagery/MapServer/tile/{z}/{y}/{x}";
                name = "卫星+路网（Esri）";
                attr = ATTR_ESRI_IMG;
                maxZ = 13;
                break;
            default:
                url = "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "World_Imagery/MapServer/tile/{z}/{y}/{x}";
                name = "卫星（Esri）";
                attr = ATTR_ESRI_IMG;
                maxZ = 18;
                break;
        }
        return new TileSource(type, PROVIDER_ESRI, DATUM_WGS84, name, url, attr,
                0, maxZ, true);
    }

    public String url(int z, int x, int y, String key) {
        String s = urlTemplate.replace("{z}", Integer.toString(z))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y));
        if (s.contains("{s}")) {
            boolean amap = s.contains("autonavi");
            int n = amap ? 4 : 8;
            int base = amap ? 1 : 0;
            s = s.replace("{s}", Integer.toString(base + ((x + y) % n)));
        }
        if (s.contains("{key}")) {
            s = s.replace("{key}", key == null ? "" : key.trim());
        }
        return s;
    }

    public String cacheId() {
        return String.format(Locale.US, "%d_%d_%08x", type, provider,
                urlTemplate.hashCode());
    }

    /** 高德透明路网/地名叠加层（卫星+路网用）。 */
    public static TileSource amapOverlay() {
        return new TileSource(TYPE_SATELLITE, PROVIDER_AMAP, DATUM_GCJ02, "高德路网",
                "https://webst0{s}.is.autonavi.com/appmaptile"
                        + "?style=8&x={x}&y={y}&z={z}",
                ATTR_AMAP, 0, 18, true, true);
    }

    /** 卫星+路网：Esri 透明路网层。 */
    public static TileSource esriTransportation() {
        return new TileSource(TYPE_SATELLITE, PROVIDER_ESRI, DATUM_WGS84, "路网",
                "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
                ATTR_ESRI_IMG, 0, 13, true, true);
    }

    /** 卫星+路网：Esri 透明地名/边界层。 */
    public static TileSource esriPlaces() {
        return new TileSource(TYPE_SATELLITE, PROVIDER_ESRI, DATUM_WGS84, "地名",
                "https://server.arcgisonline.com/ArcGIS/rest/services/"
                        + "Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
                ATTR_ESRI_IMG, 0, 13, true, true);
    }

    /** 天地图卫星注记层（cva_w）。 */
    public static TileSource tiandituLabels() {
        return new TileSource(TYPE_SATELLITE, PROVIDER_TIANDITU, DATUM_WGS84, "天地图注记",
                "https://t{s}.tianditu.gov.cn/cva_w/wmts?SERVICE=WMTS&REQUEST=GetTile"
                        + "&VERSION=1.0.0&LAYER=cva&STYLE=default&TILEMATRIXSET=w"
                        + "&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={key}",
                ATTR_TIANDITU, 1, 18, true, true);
    }
}
