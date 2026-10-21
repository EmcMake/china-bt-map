package com.btmap.china.map;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.btmap.china.track.CoordSystems;
import com.btmap.china.track.TrackStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线中国地图视图：省/市界（双 LOD）、经纬网、轨迹与定位红点。
 * 数据来自 assets/china_map.bin（CNMAP2）。
 */
public class ChinaMapView extends View {
    public interface OnCoordinatePickListener {
        void onCoordinatePicked(double lat, double lon);
    }

    public interface OnZoomLimitListener {
        void onZoomLimitReached();
    }

    private static final int COLOR_SEA = 0xFFD6E8F6;
    private static final int COLOR_LAND = 0xFFF5F0E2;
    private static final int COLOR_PROV_BORDER = 0xFF9C8A66;
    private static final int COLOR_CITY_BORDER = 0xFFC9BEA6;
    private static final int COLOR_GRID = 0x6E607D8B;
    private static final int COLOR_GRID_TEXT = 0xFF546E7A;
    private static final int COLOR_NINE_DASH = 0xFF607D8B;
    private static final int COLOR_LABEL = 0xFF3E3A32;
    private static final int COLOR_TRACK = 0xFFE53935;

    /** 默认视野：中国大陆 + 海南 + 台湾。 */
    private static final double FIT_LON_MIN = 73.3;
    private static final double FIT_LON_MAX = 135.4;
    private static final double FIT_LAT_MIN = 17.0;
    private static final double FIT_LAT_MAX = 54.0;

    /** 约 10 米/像素。 */
    private static final double MAX_WORLD_PX = 8_000_000;   // 矢量约 5m/px
    private static final double MAX_RASTER_PX = 256.0 * (1 << 17); // z17，约 1m/px

    private static final long PULSE_MS = 1800;

    private final TrackStore track = TrackStore.INSTANCE;

    private MapData data;
    private String loadError;

    private final List<DrawnFeature> provinces = new ArrayList<>();
    private final List<DrawnFeature> cities = new ArrayList<>();
    private final List<DrawnFeature> counties = new ArrayList<>();
    private DrawnFeature nineDash;

    private final Paint pLandFill = new Paint();
    private final Paint pProvBorder = new Paint();
    private final Paint pCityBorder = new Paint();
    private final Paint pCountyBorder = new Paint();
    private final Paint pGrid = new Paint();
    private final Paint pGridText = new Paint();
    private final Paint pNineDash = new Paint();
    private final Paint pLabel = new Paint();
    private final Paint pLabelHalo = new Paint();
    private final Paint pCityDot = new Paint();
    private final Paint pTrackLine = new Paint();
    private final Paint pTrackDot = new Paint();
    private final Paint pTrackDotStroke = new Paint();
    private final Paint pAccuracyFill = new Paint();
    private final Paint pAccuracyStroke = new Paint();
    private final Paint pPulse = new Paint();
    private final Paint pMarkerShadow = new Paint();
    private final Paint pMarkerWhite = new Paint();
    private final Paint pMarkerCore = new Paint();
    private final Paint pMarkerHighlight = new Paint();
    private final Paint pHeading = new Paint();
    private final Paint pBubbleShadow = new Paint();
    private final Paint pBubbleBg = new Paint();
    private final Paint pBubbleBorder = new Paint();
    private final Paint pBubble1 = new Paint();
    private final Paint pBubble2 = new Paint();
    private final Paint pOffChip = new Paint();
    private final Paint pOffText = new Paint();
    private final Paint pScaleBar = new Paint();
    private final Paint pScaleBarText = new Paint();
    private final Paint pScaleBg = new Paint();
    private final Paint pAttrBg = new Paint();
    private final Paint pHint = new Paint();
    private final Paint pAttribution = new Paint();
    private final Paint pCompass = new Paint();
    private final Paint pCompassText = new Paint();
    private final Paint pInsetBg = new Paint();
    private final Paint pInsetBorder = new Paint();
    private final Paint pInsetLabel = new Paint();

    private final Path trackPath = new Path();
    private float[] trackX = new float[1024];
    private float[] trackY = new float[1024];
    private int trackSynced;
    private long trackDroppedSynced;
    private int trackEpochSynced;
    private int trackTypeSynced = -1;

    private int mapType = TileSource.TYPE_VECTOR;
    private boolean rasterMode;
    private boolean rasterGcj;
    private double rasterMaxScale = MAX_RASTER_PX;
    private RasterTileLayer rasterLayer;
    private String mapAttribution = TileSource.ATTR_VECTOR;

    private double scale = 1;
    private double offsetX;
    private double offsetY;
    private double fitScale = 1;
    private double minScale = 1;
    private double maxScale = 1;
    private float density = 2.0f;
    private boolean initialized;
    private boolean follow = true;
    private boolean autoZoomed;

    private boolean cityVisible = true;
    private boolean countyVisible = true;
    private boolean labelsVisible = true;
    private boolean gridVisible = true;
    private boolean accuracyVisible = true;
    private boolean bubbleVisible = true;

    private boolean pinching;
    private long tapDownTime;
    private long lastTapUpTime;
    private float tapDownX;
    private float tapDownY;
    private float lastTapX;
    private float lastTapY;
    private boolean tapMoved;
    private int touchSlop;
    private float pinchStartSpan;
    private double pinchStartScale;
    private double pinchStartOffsetX;
    private double pinchStartOffsetY;
    private float pinchFocusX;
    private float pinchFocusY;

    private GestureDetector gestureDetector;
    private ValueAnimator zoomAnimator;

    private OnCoordinatePickListener pickListener;
    private OnZoomLimitListener zoomLimitListener;
    private long lastZoomLimitAt;

    private long lastFixUptime;
    private final RectF offscreenRect = new RectF();
    private boolean offscreenVisible;
    private double offscreenDistance;

    public ChinaMapView(Context context) {
        this(context, null);
    }

    public ChinaMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        touchSlop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        rasterLayer = new RasterTileLayer(getContext(), this);
        setBackgroundColor(COLOR_SEA);

        pLandFill.setStyle(Paint.Style.FILL);
        pLandFill.setColor(COLOR_LAND);
        pLandFill.setAntiAlias(true);

        pProvBorder.setStyle(Paint.Style.STROKE);
        pProvBorder.setColor(COLOR_PROV_BORDER);
        pProvBorder.setStrokeJoin(Paint.Join.ROUND);
        pProvBorder.setAntiAlias(true);

        pCityBorder.setStyle(Paint.Style.STROKE);
        pCityBorder.setColor(0xFFB9AB8F);
        pCityBorder.setAntiAlias(true);

        pCountyBorder.setStyle(Paint.Style.STROKE);
        pCountyBorder.setColor(0xFFCBBFA6);
        pCountyBorder.setAntiAlias(true);

        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setColor(COLOR_GRID);
        pGrid.setAntiAlias(true);

        pGridText.setColor(COLOR_GRID_TEXT);
        pGridText.setTextSize(dp(9.5f));
        pGridText.setAntiAlias(true);

        pNineDash.setStyle(Paint.Style.STROKE);
        pNineDash.setColor(COLOR_NINE_DASH);
        pNineDash.setStrokeCap(Paint.Cap.ROUND);
        pNineDash.setAntiAlias(true);

        pLabel.setColor(COLOR_LABEL);
        pLabel.setTextSize(dp(11f));
        pLabel.setTextAlign(Paint.Align.CENTER);
        pLabel.setFakeBoldText(true);
        pLabel.setAntiAlias(true);

        pLabelHalo.setColor(0xFFFFFFFF);
        pLabelHalo.setTextSize(dp(11f));
        pLabelHalo.setTextAlign(Paint.Align.CENTER);
        pLabelHalo.setStyle(Paint.Style.STROKE);
        pLabelHalo.setStrokeWidth(dp(2.6f));
        pLabelHalo.setFakeBoldText(true);
        pLabelHalo.setAntiAlias(true);

        pCityDot.setColor(0xFF8D6E63);
        pCityDot.setStyle(Paint.Style.FILL);
        pCityDot.setAntiAlias(true);

        pTrackLine.setColor(COLOR_TRACK);
        pTrackLine.setStyle(Paint.Style.STROKE);
        pTrackLine.setStrokeCap(Paint.Cap.ROUND);
        pTrackLine.setStrokeJoin(Paint.Join.ROUND);
        pTrackLine.setAntiAlias(true);

        pTrackDot.setColor(0xFFFFFFFF);
        pTrackDot.setStyle(Paint.Style.FILL);
        pTrackDot.setAntiAlias(true);

        pTrackDotStroke.setColor(COLOR_TRACK);
        pTrackDotStroke.setStyle(Paint.Style.STROKE);
        pTrackDotStroke.setAntiAlias(true);

        pAccuracyFill.setColor(0x22E53935);
        pAccuracyFill.setStyle(Paint.Style.FILL);
        pAccuracyFill.setAntiAlias(true);

        pAccuracyStroke.setColor(0x66E53935);
        pAccuracyStroke.setStyle(Paint.Style.STROKE);
        pAccuracyStroke.setAntiAlias(true);

        pPulse.setColor(COLOR_TRACK);
        pPulse.setStyle(Paint.Style.STROKE);
        pPulse.setAntiAlias(true);

        pMarkerShadow.setColor(0x33000000);
        pMarkerShadow.setStyle(Paint.Style.FILL);
        pMarkerShadow.setAntiAlias(true);

        pMarkerWhite.setColor(0xFFFFFFFF);
        pMarkerWhite.setStyle(Paint.Style.FILL);
        pMarkerWhite.setAntiAlias(true);

        pMarkerCore.setColor(COLOR_TRACK);
        pMarkerCore.setStyle(Paint.Style.FILL);
        pMarkerCore.setAntiAlias(true);

        pMarkerHighlight.setColor(0xB3FFFFFF);
        pMarkerHighlight.setStyle(Paint.Style.FILL);
        pMarkerHighlight.setAntiAlias(true);

        pHeading.setColor(0x66E53935);
        pHeading.setStyle(Paint.Style.FILL);
        pHeading.setAntiAlias(true);

        pBubbleShadow.setColor(0x22000000);
        pBubbleShadow.setStyle(Paint.Style.FILL);
        pBubbleShadow.setAntiAlias(true);

        pBubbleBg.setColor(0xFCFFFFFF);
        pBubbleBg.setStyle(Paint.Style.FILL);
        pBubbleBg.setAntiAlias(true);

        pBubbleBorder.setColor(0xFFDDE3E8);
        pBubbleBorder.setStyle(Paint.Style.STROKE);
        pBubbleBorder.setStrokeWidth(dp(1f));
        pBubbleBorder.setAntiAlias(true);

        pBubble1.setColor(0xFF1F2A33);
        pBubble1.setTextSize(dp(10.5f));
        pBubble1.setFakeBoldText(true);
        pBubble1.setAntiAlias(true);
        pBubble1.setTextAlign(Paint.Align.CENTER);

        pBubble2.setColor(0xFF6B7B87);
        pBubble2.setTextSize(dp(9f));
        pBubble2.setAntiAlias(true);
        pBubble2.setTextAlign(Paint.Align.CENTER);

        pOffChip.setColor(0xEEFFFFFF);
        pOffChip.setStyle(Paint.Style.FILL);
        pOffChip.setAntiAlias(true);

        pOffText.setColor(0xFFD32F2F);
        pOffText.setTextSize(dp(11f));
        pOffText.setFakeBoldText(true);
        pOffText.setAntiAlias(true);
        pOffText.setTextAlign(Paint.Align.CENTER);

        pScaleBar.setColor(0xFF37474F);
        pScaleBar.setStyle(Paint.Style.STROKE);
        pScaleBar.setStrokeWidth(dp(1.4f));
        pScaleBar.setAntiAlias(true);

        pScaleBarText.setColor(0xFF37474F);
        pScaleBg.setColor(0xE6FFFFFF);
        pScaleBg.setStyle(Paint.Style.FILL);
        pAttrBg.setColor(0xD9FFFFFF);
        pAttrBg.setStyle(Paint.Style.FILL);
        pScaleBarText.setTextSize(dp(10f));
        pScaleBarText.setAntiAlias(true);

        pHint.setColor(0xFF607D8B);
        pHint.setTextSize(dp(13f));
        pHint.setTextAlign(Paint.Align.CENTER);
        pHint.setAntiAlias(true);

        pAttribution.setColor(0x88607D8B);
        pAttribution.setTextSize(dp(8.5f));
        pAttribution.setTextAlign(Paint.Align.RIGHT);
        pAttribution.setAntiAlias(true);

        pCompass.setColor(0xAA37474F);
        pCompass.setStyle(Paint.Style.FILL);
        pCompass.setAntiAlias(true);

        pCompassText.setColor(0xCC37474F);
        pCompassText.setTextSize(dp(10f));
        pCompassText.setTextAlign(Paint.Align.CENTER);
        pCompassText.setFakeBoldText(true);
        pCompassText.setAntiAlias(true);

        pInsetBg.setColor(0xFFF2F8FC);
        pInsetBg.setStyle(Paint.Style.FILL);
        pInsetBg.setAntiAlias(true);

        pInsetBorder.setColor(0xFF90A4AE);
        pInsetBorder.setStyle(Paint.Style.STROKE);
        pInsetBorder.setStrokeWidth(dp(1f));
        pInsetBorder.setAntiAlias(true);

        pInsetLabel.setColor(0xFF546E7A);
        pInsetLabel.setTextSize(dp(8.5f));
        pInsetLabel.setAntiAlias(true);

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                cancelZoomAnimation();
                offsetX -= dx;
                offsetY -= dy;
                clampPan();
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (offscreenVisible && offscreenRect.contains(e.getX(), e.getY())) {
                    centerOnLatest();
                    invalidate();
                    return;
                }
                if (pickListener != null) {
                    double[] ll = screenToLonLat(e.getX(), e.getY());
                    pickListener.onCoordinatePicked(ll[0], ll[1]);
                }
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        load();
    }

    private void load() {
        try (InputStream in = getContext().getAssets().open("china_map.bin")) {
            data = MapData.load(in);
        } catch (IOException e) {
            loadError = "地图数据加载失败：" + e.getMessage();
            return;
        }
        for (MapData.Feature f : data.features) {
            DrawnFeature d = new DrawnFeature(f);
            if (f.level == MapData.LEVEL_PROVINCE) {
                provinces.add(d);
            } else if (f.level == MapData.LEVEL_CITY) {
                cities.add(d);
            } else if (f.level == MapData.LEVEL_COUNTY) {
                counties.add(d);
            } else {
                nineDash = d;
            }
        }
    }

    private static final class DrawnFeature {
        final MapData.Feature feature;
        private Path detail;
        private Path overview;

        DrawnFeature(MapData.Feature feature) {
            this.feature = feature;
        }

        /** 懒构建：县级有 2,700+ 要素，启动时只构建真正要画的 Path。 */
        synchronized Path pathFor(boolean useDetail) {
            if (useDetail) {
                if (detail == null) {
                    detail = buildPath(feature, feature.detail);
                }
                return detail;
            }
            if (overview == null) {
                overview = buildPath(feature, feature.overview);
            }
            return overview;
        }
    }

    private static Path buildPath(MapData.Feature f, MapData.RingSet set) {
        Path p = new Path();
        for (int r = 0; r < set.start.length; r++) {
            int start = set.start[r];
            int len = set.len[r];
            if (len < 2) {
                continue;
            }
            p.moveTo(set.xs[start], set.ys[start]);
            for (int i = 1; i < len; i++) {
                p.lineTo(set.xs[start + i], set.ys[start + i]);
            }
            if (f.level != MapData.LEVEL_NINEDASH) {
                p.close();
            }
        }
        return p;
    }

    // ------------------------------------------------------------ 公共接口
    public int getTrackCount() {
        return track.size();
    }

    public boolean isFollow() {
        return follow;
    }

    public void setFollow(boolean v) {
        follow = v;
        if (v) {
            centerOnLatest();
        }
        invalidate();
    }

    public void setPickListener(OnCoordinatePickListener l) {
        pickListener = l;
    }

    public void setOnZoomLimitListener(OnZoomLimitListener l) {
        zoomLimitListener = l;
    }

    public void setCityVisible(boolean v) {
        cityVisible = v;
        invalidate();
    }

    public void setCountyVisible(boolean v) {
        countyVisible = v;
        invalidate();
    }

    public void setLabelsVisible(boolean v) {
        labelsVisible = v;
        invalidate();
    }

    public void setGridVisible(boolean v) {
        gridVisible = v;
        invalidate();
    }

    public void setAccuracyVisible(boolean v) {
        accuracyVisible = v;
        invalidate();
    }

    public void setBubbleVisible(boolean v) {
        bubbleVisible = v;
        invalidate();
    }

    /**
     * 切换底图类型。type 为 TileSource.TYPE_*；栅格图层需要网络。
     */
    public void setMapType(int type, int provider, String tiandituKey) {
        mapType = type;
        rasterMode = type != TileSource.TYPE_VECTOR;
        if (rasterMode) {
            int baseType = type == TileSource.TYPE_HYBRID
                    ? TileSource.TYPE_SATELLITE : type;
            TileSource base = TileSource.forRaster(baseType, provider, tiandituKey);
            rasterGcj = base.datum == TileSource.DATUM_GCJ02;
            rasterMaxScale = Math.min(MAX_RASTER_PX,
                    256.0 * (1 << Math.max(0, base.maxZoom)));
            rasterLayer.setKey(tiandituKey);
            if (type == TileSource.TYPE_HYBRID) {
                if (base.provider == TileSource.PROVIDER_TIANDITU) {
                    rasterLayer.setSources(base, TileSource.tiandituLabels());
                } else if (base.provider == TileSource.PROVIDER_AMAP) {
                    rasterLayer.setSources(base, TileSource.amapOverlay());
                } else {
                    rasterLayer.setSources(base, TileSource.esriTransportation(),
                            TileSource.esriPlaces());
                }
            } else {
                rasterLayer.setSources(base);
            }
            mapAttribution = base.attribution;
        } else {
            rasterLayer.setSources(null);
            mapAttribution = TileSource.ATTR_VECTOR;
        }
        updateZoomLimits();
        trackSynced = 0;
        trackTypeSynced = -1;
        trackPath.reset();
        invalidate();
    }

    public int getMapType() {
        return mapType;
    }

    public boolean isRasterMode() {
        return rasterMode;
    }

    public long tileCacheBytes() {
        return rasterLayer.cacheBytes();
    }

    public void clearTileCache() {
        rasterLayer.clearCache();
    }

    private void updateZoomLimits() {
        if (fitScale <= 1) {
            return;
        }
        maxScale = rasterMode ? rasterMaxScale
                : Math.min(fitScale * 1500, MAX_WORLD_PX);
        if (scale > maxScale) {
            scale = maxScale;
            clampPan();
        }
    }

    private double displayLat(int i) {
        return rasterMode && !rasterGcj ? track.wgsLat(i) : track.mapLat(i);
    }

    private double displayLon(int i) {
        return rasterMode && !rasterGcj ? track.wgsLon(i) : track.mapLon(i);
    }

    private double[] lastDisplayLatLon() {
        int n = track.size();
        if (n == 0) {
            return null;
        }
        return new double[]{displayLat(n - 1), displayLon(n - 1)};
    }

    // ------------------------------------------------------------ 视图变换
    public void fitChina() {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        cancelZoomAnimation();
        double x0 = Mercator.lonToX(FIT_LON_MIN);
        double x1 = Mercator.lonToX(FIT_LON_MAX);
        double y0 = Mercator.latToY(FIT_LAT_MAX);
        double y1 = Mercator.latToY(FIT_LAT_MIN);
        fitScale = Math.min(getWidth() / (x1 - x0), getHeight() / (y1 - y0)) * 0.94;
        minScale = fitScale * 0.6;
        updateZoomLimits();
        scale = fitScale;
        offsetX = getWidth() / 2.0 - (x0 + x1) / 2.0 * scale;
        offsetY = getHeight() / 2.0 - (y0 + y1) / 2.0 * scale;
        invalidate();
    }

    public void zoomBy(double factor) {
        animateZoom(getWidth() / 2.0f, getHeight() / 2.0f, factor);
    }

    private void animateZoom(float fx, float fy, double factor) {
        cancelZoomAnimation();
        final double startScale = scale;
        final double target = clampScale(startScale * factor, factor > 1);
        if (Math.abs(target - startScale) < 1e-9) {
            return;
        }
        final double startX = offsetX;
        final double startY = offsetY;
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(180);
        zoomAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            double s = startScale * Math.pow(target / startScale, t);
            double k = s / startScale;
            scale = s;
            offsetX = fx - (fx - startX) * k;
            offsetY = fy - (fy - startY) * k;
            clampPan();
            invalidate();
        });
        zoomAnimator.start();
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null && zoomAnimator.isRunning()) {
            zoomAnimator.cancel();
        }
        zoomAnimator = null;
    }

    private double clampScale(double s, boolean zoomingIn) {
        double out = Math.max(minScale, Math.min(maxScale, s));
        if (s > maxScale && zoomingIn) {
            long now = SystemClock.uptimeMillis();
            if (now - lastZoomLimitAt > 2500 && zoomLimitListener != null) {
                lastZoomLimitAt = now;
                zoomLimitListener.onZoomLimitReached();
            }
        }
        return out;
    }

    private void clampPan() {
        if (data == null) {
            return;
        }
        double padX = (data.maxX - data.minX) * 0.35 + 0.01;
        double padY = (data.maxY - data.minY) * 0.35 + 0.01;
        double cx = (getWidth() / 2.0 - offsetX) / scale;
        double cy = (getHeight() / 2.0 - offsetY) / scale;
        double ncx = Math.max(data.minX - padX, Math.min(data.maxX + padX, cx));
        double ncy = Math.max(data.minY - padY, Math.min(data.maxY + padY, cy));
        if (ncx != cx) {
            offsetX = getWidth() / 2.0 - ncx * scale;
        }
        if (ncy != cy) {
            offsetY = getHeight() / 2.0 - ncy * scale;
        }
    }

    public double[] screenToLonLat(float sx, float sy) {
        double wx = (sx - offsetX) / scale;
        double wy = (sy - offsetY) / scale;
        return new double[]{Mercator.yToLat(wy), Mercator.xToLon(wx)};
    }

    public float[] lonLatToScreen(double lat, double lon) {
        return new float[]{
                (float) (Mercator.lonToX(lon) * scale + offsetX),
                (float) (Mercator.latToY(lat) * scale + offsetY)
        };
    }

    private void centerOnLatest() {
        double[] ll = lastDisplayLatLon();
        if (ll == null) {
            return;
        }
        double wx = Mercator.lonToX(ll[1]);
        double wy = Mercator.latToY(ll[0]);
        offsetX = getWidth() / 2.0 - wx * scale;
        offsetY = getHeight() / 2.0 - wy * scale;
    }

    public void focusOn(double lat, double lon, boolean zoomInIfFar) {
        double wx = Mercator.lonToX(lon);
        double wy = Mercator.latToY(lat);
        offsetX = getWidth() / 2.0 - wx * scale;
        offsetY = getHeight() / 2.0 - wy * scale;
        if (zoomInIfFar && scale < fitScale * 8) {
            scale = Math.min(maxScale, fitScale * 20);
            offsetX = getWidth() / 2.0 - wx * scale;
            offsetY = getHeight() / 2.0 - wy * scale;
        }
        clampPan();
        invalidate();
    }

    public void fitTrack() {
        int n = track.size();
        if (n == 0 || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        if (n == 1) {
            scale = clampScale(fitScale * 60, false);
            centerOnLatest();
            clampPan();
            invalidate();
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float x = (float) Mercator.lonToX(displayLon(i));
            float y = (float) Mercator.latToY(displayLat(i));
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        double w = Math.max(1e-9, maxX - minX);
        double h = Math.max(1e-9, maxY - minY);
        double target = Math.min(getWidth() * 0.80 / w, getHeight() * 0.70 / h);
        scale = clampScale(Math.min(target, fitScale * 200), false);
        offsetX = getWidth() / 2.0 - (minX + maxX) / 2.0 * scale;
        offsetY = getHeight() / 2.0 - (minY + maxY) / 2.0 * scale;
        clampPan();
        invalidate();
    }

    // ------------------------------------------------------------ 轨迹
    public void onTrackChanged() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post(this::onTrackChanged);
            return;
        }
        int n = track.size();
        if (n > 0 && !autoZoomed) {
            autoZoomed = true;
            follow = true;
            fitTrack();
        } else if (follow && n > 0) {
            centerOnLatest();
            clampPan();
        }
        invalidate();
    }

    private void syncTrack() {
        if (trackTypeSynced != mapType) {
            trackTypeSynced = mapType;
            trackSynced = 0;
            trackPath.reset();
        }
        int n = track.size();
        long dropped = track.droppedCount();
        int epoch = track.datumEpoch();
        if (dropped != trackDroppedSynced || epoch != trackEpochSynced) {
            trackDroppedSynced = dropped;
            trackEpochSynced = epoch;
            trackSynced = 0;
            trackPath.reset();
        }
        if (n < trackSynced) {
            trackSynced = 0;
            trackPath.reset();
        }
        boolean added = false;
        for (int i = trackSynced; i < n; i++) {
            float x = (float) Mercator.lonToX(track.mapLon(i));
            float y = (float) Mercator.latToY(track.mapLat(i));
            if (trackSynced == 0) {
                trackPath.moveTo(x, y);
            } else {
                trackPath.lineTo(x, y);
            }
            if (i >= trackX.length) {
                int cap = Math.min(TrackStore.MAX_POINTS, trackX.length * 2);
                float[] nx = new float[cap];
                float[] ny = new float[cap];
                System.arraycopy(trackX, 0, nx, 0, trackX.length);
                System.arraycopy(trackY, 0, ny, 0, trackY.length);
                trackX = nx;
                trackY = ny;
            }
            trackX[i] = x;
            trackY[i] = y;
            trackSynced = i + 1;
            added = true;
        }
        if (added) {
            lastFixUptime = SystemClock.uptimeMillis();
        }
    }

    // ------------------------------------------------------------ 绘制
    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(COLOR_SEA);
        if (data == null) {
            canvas.drawText(loadError == null ? "地图数据缺失" : loadError,
                    getWidth() / 2f, getHeight() / 2f, pHint);
            return;
        }
        float s = (float) scale;
        if (rasterMode) {
            trackSynced = track.size();
            canvas.drawColor(0xFFE9EEF2);
            rasterLayer.draw(canvas, scale, offsetX, offsetY, getWidth(), getHeight());
            canvas.save();
            canvas.translate((float) offsetX, (float) offsetY);
            canvas.scale(s, s);
            if (gridVisible) {
                pGrid.setStrokeWidth(dp(0.7f) / s);
                drawGraticule(canvas);
            }
            canvas.restore();
            if (gridVisible) {
                drawGridLabels(canvas);
            }
            drawRasterTrack(canvas);
            drawLatest(canvas);
            drawOffscreenHint(canvas);
            drawHint(canvas);
            drawScaleBar(canvas);
            drawCompass(canvas);
            drawAttribution(canvas);
            long age = SystemClock.uptimeMillis() - lastFixUptime;
            if (age < PULSE_MS && trackSynced > 0) {
                postInvalidateOnAnimation();
            }
            return;
        }
        syncTrack();
        boolean useDetail = scale >= fitScale * 3.0;
        float wx0 = (float) ((0 - offsetX) / scale);
        float wx1 = (float) ((getWidth() - offsetX) / scale);
        float wy0 = (float) ((0 - offsetY) / scale);
        float wy1 = (float) ((getHeight() - offsetY) / scale);

        canvas.save();
        canvas.translate((float) offsetX, (float) offsetY);
        canvas.scale(s, s);

        for (DrawnFeature d : provinces) {
            if (!visible(d, wx0, wy0, wx1, wy1)) {
                continue;
            }
            canvas.drawPath(d.pathFor(useDetail), pLandFill);
        }

        if (gridVisible) {
            pGrid.setStrokeWidth(dp(0.7f) / s);
            drawGraticule(canvas);
        }

        boolean cityLayer = cityVisible && scale > fitScale * 2.2;
        boolean countyLayer = countyVisible && scale > fitScale * 4.0
                && !counties.isEmpty();
        if (!cityLayer) {
            // 低缩放只画省界；放大后改用更细的市/县网络，
            // 避免 DataV 多套几何叠加出现“双线”
            pProvBorder.setStrokeWidth(dp(1.1f) / s);
            for (DrawnFeature d : provinces) {
                if (!visible(d, wx0, wy0, wx1, wy1)) {
                    continue;
                }
                canvas.drawPath(d.pathFor(useDetail), pProvBorder);
            }
        }

        if (cityLayer) {
            pCityBorder.setStrokeWidth(dp(0.9f) / s);
            for (DrawnFeature d : cities) {
                if (!visible(d, wx0, wy0, wx1, wy1)) {
                    continue;
                }
                canvas.drawPath(d.pathFor(useDetail), pCityBorder);
            }
        }
        if (countyLayer) {
            pCountyBorder.setStrokeWidth(dp(0.55f) / s);
            for (DrawnFeature d : counties) {
                if (!visible(d, wx0, wy0, wx1, wy1)) {
                    continue;
                }
                canvas.drawPath(d.pathFor(useDetail), pCountyBorder);
            }
        }

        if (nineDash != null) {
            pNineDash.setStrokeWidth(dp(1.8f) / s);
            canvas.drawPath(nineDash.pathFor(useDetail), pNineDash);
        }

        if (trackSynced > 1) {
            pTrackLine.setStrokeWidth(dp(2.4f) / s);
            canvas.drawPath(trackPath, pTrackLine);
            if (trackSynced <= 2000) {
                pTrackDotStroke.setStrokeWidth(dp(1.2f) / s);
                float r = dp(3.0f) / s;
                for (int i = 0; i < trackSynced; i++) {
                    canvas.drawCircle(trackX[i], trackY[i], r, pTrackDot);
                    canvas.drawCircle(trackX[i], trackY[i], r, pTrackDotStroke);
                }
            }
        } else if (trackSynced == 1) {
            float r = dp(3.5f) / s;
            pTrackDotStroke.setStrokeWidth(dp(1.4f) / s);
            canvas.drawCircle(trackX[0], trackY[0], r, pTrackDot);
            canvas.drawCircle(trackX[0], trackY[0], r, pTrackDotStroke);
        }

        canvas.restore();

        if (gridVisible) {
            drawGridLabels(canvas);
        }
        if (labelsVisible) {
            drawLabels(canvas);
        }
        drawInset(canvas);
        drawLatest(canvas);
        drawOffscreenHint(canvas);
        drawHint(canvas);
        drawScaleBar(canvas);
        drawCompass(canvas);
        drawAttribution(canvas);

        long age = SystemClock.uptimeMillis() - lastFixUptime;
        if (age < PULSE_MS && trackSynced > 0) {
            postInvalidateOnAnimation();
        }
    }

    private static boolean visible(DrawnFeature d, float wx0, float wy0, float wx1, float wy1) {
        return d.feature.maxX >= wx0 && d.feature.minX <= wx1
                && d.feature.maxY >= wy0 && d.feature.minY <= wy1;
    }

    private double gridStep() {
        double pxPerDeg = scale / 360.0;
        double[] steps = {0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 30};
        for (double candidate : steps) {
            if (candidate * pxPerDeg >= dp(90)) {
                return candidate;
            }
        }
        return steps[steps.length - 1];
    }

    private void drawGraticule(Canvas canvas) {
        double step = gridStep();
        double[] tl = screenToLonLat(0, 0);
        double[] br = screenToLonLat(getWidth(), getHeight());
        double lonMin = Math.min(tl[1], br[1]) - step;
        double lonMax = Math.max(tl[1], br[1]) + step;
        double latMin = Math.min(tl[0], br[0]) - step;
        double latMax = Math.max(tl[0], br[0]) + step;
        Path grid = new Path();
        for (double lon = Math.ceil(lonMin / step) * step; lon <= lonMax; lon += step) {
            float x = (float) Mercator.lonToX(lon);
            grid.moveTo(x, (float) Mercator.latToY(Math.max(-85, latMin)));
            grid.lineTo(x, (float) Mercator.latToY(Math.min(85, latMax)));
        }
        for (double lat = Math.ceil(latMin / step) * step; lat <= latMax; lat += step) {
            if (Math.abs(lat) > 85) {
                continue;
            }
            float y = (float) Mercator.latToY(lat);
            grid.moveTo((float) Mercator.lonToX(lonMin), y);
            grid.lineTo((float) Mercator.lonToX(lonMax), y);
        }
        canvas.drawPath(grid, pGrid);
    }

    private void drawGridLabels(Canvas canvas) {
        double step = gridStep();
        int decimals = step >= 0.1 ? 2 : 3;
        String fmt = "%." + decimals + "f°";
        double[] tl = screenToLonLat(0, 0);
        double[] br = screenToLonLat(getWidth(), getHeight());
        double lonMin = Math.min(tl[1], br[1]) - step;
        double lonMax = Math.max(tl[1], br[1]) + step;
        double latMin = Math.min(tl[0], br[0]) - step;
        double latMax = Math.max(tl[0], br[0]) + step;
        pGridText.setTextSize(dp(9.5f));
        float topY = dp(104f);
        float bottomY = getHeight() - dp(116f);
        for (double lon = Math.ceil(lonMin / step) * step; lon <= lonMax; lon += step) {
            float x = (float) (Mercator.lonToX(lon) * scale + offsetX);
            if (x < dp(28) || x > getWidth() - dp(28)) {
                continue;
            }
            String text = String.format(Locale.US, fmt, lon) + "E";
            canvas.drawText(text, x, topY, pGridText);
            canvas.drawText(text, x, bottomY, pGridText);
        }
        for (double lat = Math.ceil(latMin / step) * step; lat <= latMax; lat += step) {
            if (Math.abs(lat) > 85) {
                continue;
            }
            float y = (float) (Mercator.latToY(lat) * scale + offsetY);
            if (y < dp(120) || y > getHeight() - dp(124)) {
                continue;
            }
            String text = String.format(Locale.US, fmt, lat) + "N";
            canvas.drawText(text, dp(6f), y - dp(2f), pGridText);
        }
    }

    private void drawLabels(Canvas canvas) {
        if (scale > fitScale * 1.6) {
            float r = dp(2.2f);
            for (DrawnFeature d : cities) {
                if (!d.feature.hasCenter()) {
                    continue;
                }
                float[] pt = lonLatToScreen(d.feature.centerLat, d.feature.centerLon);
                if (!inMapArea(pt[0], pt[1], dp(20f))) {
                    continue;
                }
                canvas.drawCircle(pt[0], pt[1], r, pCityDot);
            }
        }
        pLabel.setFakeBoldText(true);
        pLabelHalo.setFakeBoldText(true);
        pLabel.setColor(COLOR_LABEL);
        pLabel.setTextSize(dp(11f));
        pLabelHalo.setTextSize(dp(11f));
        for (DrawnFeature d : provinces) {
            if (!d.feature.hasCenter()) {
                continue;
            }
            float[] pt = lonLatToScreen(d.feature.centerLat, d.feature.centerLon);
            if (!inMapArea(pt[0], pt[1], 0)) {
                continue;
            }
            String label = shortName(d.feature.name);
            if (scale < fitScale * 1.3 && !isMajorProvince(label)) {
                continue;
            }
            canvas.drawText(label, pt[0], pt[1], pLabelHalo);
            canvas.drawText(label, pt[0], pt[1], pLabel);
        }
        if (scale > fitScale * 3.5) {
            pLabel.setTextSize(dp(9.5f));
            pLabelHalo.setTextSize(dp(9.5f));
            pLabel.setFakeBoldText(false);
            pLabelHalo.setFakeBoldText(false);
            pLabel.setColor(0xFF6D5B45);
            int drawn = 0;
            for (DrawnFeature d : cities) {
                if (!d.feature.hasCenter() || d.feature.name.isEmpty()) {
                    continue;
                }
                float[] pt = lonLatToScreen(d.feature.centerLat, d.feature.centerLon);
                if (!inMapArea(pt[0], pt[1], dp(10f))) {
                    continue;
                }
                canvas.drawText(d.feature.name, pt[0], pt[1] - dp(5f), pLabelHalo);
                canvas.drawText(d.feature.name, pt[0], pt[1] - dp(5f), pLabel);
                if (++drawn > 120) {
                    break;
                }
            }
            pLabel.setFakeBoldText(true);
            pLabelHalo.setFakeBoldText(true);
            pLabel.setColor(COLOR_LABEL);
        }
        if (scale > fitScale * 8.0 && countyVisible && !counties.isEmpty()) {
            pLabel.setTextSize(dp(8.5f));
            pLabelHalo.setTextSize(dp(8.5f));
            pLabel.setFakeBoldText(false);
            pLabelHalo.setFakeBoldText(false);
            pLabel.setColor(0xFF7A6850);
            int drawn = 0;
            for (DrawnFeature d : counties) {
                if (!d.feature.hasCenter() || d.feature.name.isEmpty()) {
                    continue;
                }
                float[] pt = lonLatToScreen(d.feature.centerLat, d.feature.centerLon);
                if (!inMapArea(pt[0], pt[1], dp(8f))) {
                    continue;
                }
                canvas.drawText(d.feature.name, pt[0], pt[1], pLabelHalo);
                canvas.drawText(d.feature.name, pt[0], pt[1], pLabel);
                if (++drawn > 200) {
                    break;
                }
            }
            pLabel.setFakeBoldText(true);
            pLabelHalo.setFakeBoldText(true);
            pLabel.setColor(COLOR_LABEL);
        }
    }

    /** 是否落在可视地图区域（避开顶部/底部悬浮控件与南海小图）。 */
    private boolean inMapArea(float x, float y, float margin) {
        if (x < margin || x > getWidth() - margin) {
            return false;
        }
        if (y < dp(100f) + margin || y > getHeight() - dp(112f) - margin) {
            return false;
        }
        return scale >= fitScale * 6 || !insetRect().contains(x, y);
    }

    private static boolean isMajorProvince(String s) {
        switch (s) {
            case "北京":
            case "上海":
            case "天津":
            case "重庆":
            case "广东":
            case "四川":
            case "新疆":
            case "西藏":
            case "内蒙古":
            case "黑龙江":
            case "云南":
            case "陕西":
            case "河南":
            case "山东":
            case "江苏":
            case "浙江":
            case "福建":
            case "湖北":
            case "湖南":
            case "广西":
            case "辽宁":
            case "甘肃":
            case "青海":
            case "海南":
            case "台湾":
                return true;
            default:
                return false;
        }
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        String s = name;
        String[] suffixes = {"维吾尔自治区", "壮族自治区", "回族自治区", "特别行政区", "自治区", "省", "市"};
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        return s;
    }

    // ------------------------------------------------------------ 南海诸岛小图
    private RectF insetRect() {
        float w = dp(96f);
        float h = dp(84f);
        float right = getWidth() - dp(10f);
        float bottom = getHeight() - dp(104f);
        return new RectF(right - w, bottom - h, right, bottom);
    }

    private void drawInset(Canvas canvas) {
        if (scale > fitScale * 6 || getWidth() < dp(320f)) {
            return;
        }
        RectF r = insetRect();
        canvas.drawRoundRect(r, dp(6f), dp(6f), pInsetBg);

        double lon0 = Mercator.lonToX(106.0);
        double lon1 = Mercator.lonToX(123.0);
        double latTop = Mercator.latToY(24.0);
        double latBottom = Mercator.latToY(2.5);
        double w = r.width(), h = r.height();
        double s = Math.min(w / (lon1 - lon0), h / (latBottom - latTop)) * 0.92;
        float padX = (float) ((w - (lon1 - lon0) * s) / 2);
        float padY = (float) ((h - (latBottom - latTop) * s) / 2);

        canvas.save();
        canvas.clipRect(r);
        canvas.translate(r.left + padX, r.top + padY);
        canvas.translate((float) (-lon0 * s), (float) (-latTop * s));
        canvas.scale((float) s, (float) s);

        for (DrawnFeature d : provinces) {
            if (d.feature.maxX < lon0 || d.feature.minX > lon1
                    || d.feature.maxY < latTop || d.feature.minY > latBottom) {
                continue;
            }
            canvas.drawPath(d.pathFor(false), pLandFill);
            pInsetBorder.setStrokeWidth(dp(0.6f) / (float) s);
            canvas.drawPath(d.pathFor(false), pInsetBorder);
        }
        if (nineDash != null) {
            pNineDash.setStrokeWidth(dp(1.2f) / (float) s);
            canvas.drawPath(nineDash.pathFor(true), pNineDash);
        }
        canvas.restore();

        canvas.drawRoundRect(r, dp(6f), dp(6f), pInsetBorder);
        canvas.drawText("南海诸岛", r.left + dp(5f), r.bottom - dp(5f), pInsetLabel);
    }

    // ------------------------------------------------------------ 定位红点
    /** 栅格底图下用 WGS-84 + double 屏幕坐标画轨迹，避免深度缩放时的 float 抖动。 */
    private void drawRasterTrack(Canvas canvas) {
        int n = track.size();
        if (n <= 0) {
            return;
        }
        if (n > trackX.length) {
            int cap = Math.min(TrackStore.MAX_POINTS, Math.max(n, trackX.length * 2));
            float[] nx = new float[cap];
            float[] ny = new float[cap];
            System.arraycopy(trackX, 0, nx, 0, trackX.length);
            System.arraycopy(trackY, 0, ny, 0, trackY.length);
            trackX = nx;
            trackY = ny;
        }
        Path p = new Path();
        for (int i = 0; i < n; i++) {
            double x = Mercator.lonToX(displayLon(i)) * scale + offsetX;
            double y = Mercator.latToY(displayLat(i)) * scale + offsetY;
            trackX[i] = (float) x;
            trackY[i] = (float) y;
            if (i == 0) {
                p.moveTo((float) x, (float) y);
            } else {
                p.lineTo((float) x, (float) y);
            }
        }
        if (n > 1) {
            pTrackLine.setStrokeWidth(dp(2.4f));
            canvas.drawPath(p, pTrackLine);
            if (n <= 2000) {
                pTrackDotStroke.setStrokeWidth(dp(1.2f));
                float r = dp(3.0f);
                for (int i = 0; i < n; i++) {
                    canvas.drawCircle(trackX[i], trackY[i], r, pTrackDot);
                    canvas.drawCircle(trackX[i], trackY[i], r, pTrackDotStroke);
                }
            }
        } else {
            float r = dp(3.5f);
            pTrackDotStroke.setStrokeWidth(dp(1.4f));
            canvas.drawCircle(trackX[0], trackY[0], r, pTrackDot);
            canvas.drawCircle(trackX[0], trackY[0], r, pTrackDotStroke);
        }
    }

    private void drawLatest(Canvas canvas) {
        if (trackSynced == 0) {
            return;
        }
        int last = trackSynced - 1;
        double lat = displayLat(last);
        double lon = displayLon(last);
        float[] pt = lonLatToScreen(lat, lon);
        float cx = pt[0];
        float cy = pt[1];
        if (cx < -dp(4f) || cx > getWidth() + dp(4f)
                || cy < -dp(4f) || cy > getHeight() + dp(4f)) {
            return; // 点在屏幕外，交给边缘指示处理
        }

        // 精度圈
        double acc = track.accuracy(trackSynced - 1);
        if (accuracyVisible && acc > 0.5) {
            double world = acc / Mercator.metersPerWorldUnit(lat);
            float rp = (float) (world * scale);
            rp = Math.max(dp(8f), Math.min(dp(220f), rp));
            canvas.drawCircle(cx, cy, rp, pAccuracyFill);
            pAccuracyStroke.setStrokeWidth(dp(1f));
            canvas.drawCircle(cx, cy, rp, pAccuracyStroke);
        }

        // 脉冲
        long age = SystemClock.uptimeMillis() - lastFixUptime;
        if (age < PULSE_MS) {
            float t = age / (float) PULSE_MS;
            drawPulse(canvas, cx, cy, t);
            drawPulse(canvas, cx, cy, (t + 0.5f) % 1f);
        }

        // 航向锥
        if (trackSynced >= 2) {
            double lat0 = displayLat(trackSynced - 2);
            double lon0 = displayLon(trackSynced - 2);
            double d = Mercator.distanceMeters(lat0, lon0, lat, lon);
            if (d > 3.0) {
                double bearing = Math.toDegrees(Math.atan2(
                        (lon - lon0) * Math.cos(Math.toRadians(lat)), lat - lat0));
                canvas.save();
                canvas.translate(cx, cy);
                canvas.rotate((float) bearing);
                float r = dp(26f);
                Path cone = new Path();
                cone.moveTo(0, 0);
                cone.arcTo(new RectF(-r, -r, r, r), -120f, 60f, false);
                cone.close();
                canvas.drawPath(cone, pHeading);
                canvas.restore();
            }
        }

        // 阴影 + 白环 + 红核 + 高光
        canvas.drawCircle(cx, cy + dp(2f), dp(10f), pMarkerShadow);
        canvas.drawCircle(cx, cy, dp(9f), pMarkerWhite);
        canvas.drawCircle(cx, cy, dp(6f), pMarkerCore);
        canvas.drawCircle(cx - dp(2f), cy - dp(2f), dp(2f), pMarkerHighlight);

        if (bubbleVisible) {
            drawBubble(canvas, cx, cy, lat, lon, track.accuracy(last),
                    rasterMode && !rasterGcj ? "WGS-84" : "GCJ-02", track.time(last));
        }
    }

    private void drawPulse(Canvas canvas, float cx, float cy, float t) {
        float r = dp(9f) + dp(17f) * t;
        int alpha = (int) (80 * (1f - t));
        if (alpha <= 0) {
            return;
        }
        pPulse.setAlpha(alpha);
        pPulse.setStrokeWidth(dp(1.4f));
        canvas.drawCircle(cx, cy, r, pPulse);
    }

    private void drawBubble(Canvas canvas, float cx, float cy, double lat, double lon,
                            double accuracy, String datumName, long time) {
        String line1 = String.format(Locale.US, "%.6f, %.6f", lat, lon);
        StringBuilder sb = new StringBuilder(datumName);
        if (accuracy > 0.5) {
            sb.append(String.format(Locale.US, " · ±%.0fm", accuracy));
        }
        sb.append(String.format(Locale.US, " · %tH:%tM:%tS", time, time, time));
        String line2 = sb.toString();

        float pad = dp(8f);
        float w = Math.max(pBubble1.measureText(line1), pBubble2.measureText(line2)) + pad * 2;
        float h = dp(34f);
        float bx = cx - w / 2f;
        float by = cy - dp(16f) - h;
        boolean below = false;
        if (by < dp(100f)) {
            by = cy + dp(16f);
            below = true;
        }
        bx = Math.max(dp(6f), Math.min(getWidth() - w - dp(6f), bx));
        float maxY = getHeight() - dp(116f) - h;
        if (by > maxY) {
            by = maxY;
        }
        if (by < dp(100f)) {
            by = dp(100f);
        }

        RectF rect = new RectF(bx, by, bx + w, by + h);
        RectF shadow = new RectF(bx, by + dp(2f), bx + w, by + h + dp(2f));
        canvas.drawRoundRect(shadow, dp(8f), dp(8f), pBubbleShadow);
        canvas.drawRoundRect(rect, dp(8f), dp(8f), pBubbleBg);
        canvas.drawRoundRect(rect, dp(8f), dp(8f), pBubbleBorder);

        float px = Math.max(bx + dp(10f), Math.min(bx + w - dp(10f), cx));
        Path tri = new Path();
        if (below) {
            tri.moveTo(px - dp(5f), by);
            tri.lineTo(px + dp(5f), by);
            tri.lineTo(px, by - dp(6f));
        } else {
            tri.moveTo(px - dp(5f), by + h);
            tri.lineTo(px + dp(5f), by + h);
            tri.lineTo(px, by + h + dp(6f));
        }
        canvas.drawPath(tri, pBubbleBg);

        canvas.drawText(line1, bx + w / 2f, by + dp(14f), pBubble1);
        canvas.drawText(line2, bx + w / 2f, by + dp(28f), pBubble2);
    }

    private void drawOffscreenHint(Canvas canvas) {
        offscreenVisible = false;
        if (trackSynced == 0) {
            return;
        }
        int lastIdx = track.size() - 1;
        double lat = displayLat(lastIdx);
        double lon = displayLon(lastIdx);
        float[] pt = lonLatToScreen(lat, lon);
        float left = dp(16f), top = dp(108f);
        float right = getWidth() - dp(16f), bottom = getHeight() - dp(120f);
        if (pt[0] >= left && pt[0] <= right && pt[1] >= top && pt[1] <= bottom) {
            return;
        }
        float ax = Math.max(left, Math.min(right, pt[0]));
        float ay = Math.max(top, Math.min(bottom, pt[1]));

        double[] center = screenToLonLat(getWidth() / 2f, getHeight() / 2f);
        double dist = Mercator.distanceMeters(center[0], center[1], lat, lon);
        String text = dist >= 1000
                ? String.format(Locale.US, "↗ %.1f km", dist / 1000)
                : String.format(Locale.US, "↗ %.0f m", dist);
        float w = pOffText.measureText(text) + dp(18f);
        float h = dp(30f);
        float bx = Math.max(dp(8f), Math.min(getWidth() - w - dp(8f), ax - w / 2f));
        float by = Math.max(dp(142f), Math.min(getHeight() - dp(124f) - h, ay - h / 2f));
        RectF r = new RectF(bx, by, bx + w, by + h);
        canvas.drawRoundRect(r, h / 2f, h / 2f, pOffChip);
        pBubbleBorder.setStrokeWidth(dp(1f));
        canvas.drawRoundRect(r, h / 2f, h / 2f, pBubbleBorder);
        canvas.drawText(text, bx + w / 2f, by + h / 2f + pOffText.getTextSize() * 0.36f, pOffText);
        offscreenRect.set(r);
        offscreenDistance = dist;
        offscreenVisible = true;
    }

    // ------------------------------------------------------------ 其它装饰
    private void drawHint(Canvas canvas) {
        if (trackSynced > 0 || loadError != null) {
            return;
        }
        canvas.drawText("等待蓝牙坐标…", getWidth() / 2f, getHeight() * 0.32f, pHint);
    }

    private void drawScaleBar(Canvas canvas) {
        double lat = screenToLonLat(getWidth() / 2f, getHeight() / 2f)[0];
        double metersPerPx = Mercator.metersPerWorldUnit(lat) / scale;
        double target = metersPerPx * dp(90);
        double nice = niceNumber(target);
        float len = (float) (nice / metersPerPx);
        float x0 = dp(16f);
        float y0 = getHeight() - dp(128f);
        float x1 = x0 + len;
        String label = nice >= 1000
                ? String.format(Locale.US, "%.0f km", nice / 1000)
                : String.format(Locale.US, "%.0f m", nice);
        float tw = pScaleBarText.measureText(label);
        RectF bg = new RectF(x0 - dp(7f), y0 - dp(28f),
                Math.max(x1, x0 + tw) + dp(7f), y0 + dp(9f));
        canvas.drawRoundRect(bg, dp(5f), dp(5f), pScaleBg);
        Path bar = new Path();
        bar.moveTo(x0, y0);
        bar.lineTo(x1, y0);
        bar.moveTo(x0, y0 - dp(4f));
        bar.lineTo(x0, y0 + dp(4f));
        bar.moveTo(x1, y0 - dp(4f));
        bar.lineTo(x1, y0 + dp(4f));
        canvas.drawPath(bar, pScaleBar);
        canvas.drawText(label, x0, y0 - dp(6f), pScaleBarText);
    }

    private static double niceNumber(double v) {
        if (v <= 0) {
            return 1;
        }
        double exp = Math.floor(Math.log10(v));
        double base = Math.pow(10, exp);
        double f = v / base;
        double nice;
        if (f <= 1) {
            nice = 1;
        } else if (f <= 2) {
            nice = 2;
        } else if (f <= 5) {
            nice = 5;
        } else {
            nice = 10;
        }
        return nice * base;
    }

    private void drawCompass(Canvas canvas) {
        float cx = getWidth() - dp(22f);
        float cy = dp(102f);
        Path p = new Path();
        p.moveTo(cx, cy - dp(12f));
        p.lineTo(cx - dp(5f), cy + dp(5f));
        p.lineTo(cx, cy + dp(2f));
        p.lineTo(cx + dp(5f), cy + dp(5f));
        p.close();
        canvas.drawPath(p, pCompass);
        canvas.drawText("N", cx, cy + dp(15f), pCompassText);
    }

    private void drawAttribution(Canvas canvas) {
        float y = getHeight() - dp(120f);
        float tw = pAttribution.measureText(mapAttribution);
        RectF bg = new RectF(getWidth() - tw - dp(12f), y - pAttribution.getTextSize(),
                getWidth() - dp(4f), y + dp(4f));
        canvas.drawRoundRect(bg, dp(3f), dp(3f), pAttrBg);
        canvas.drawText(mapAttribution, getWidth() - dp(8f), y, pAttribution);
    }

    private float dp(float v) {
        return v * density;
    }

    // ------------------------------------------------------------ 触摸
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (offscreenVisible && offscreenRect.contains(event.getX(), event.getY())) {
                centerOnLatest();
                clampPan();
                invalidate();
                return true;
            }
            cancelZoomAnimation();
            tapDownTime = SystemClock.uptimeMillis();
            tapDownX = event.getX();
            tapDownY = event.getY();
            tapMoved = false;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            if (Math.hypot(event.getX() - tapDownX, event.getY() - tapDownY) > touchSlop) {
                tapMoved = true;
            }
        }

        if (action == MotionEvent.ACTION_UP && !pinching) {
            long now = SystemClock.uptimeMillis();
            if (!tapMoved && now - tapDownTime < 300) {
                if (now - lastTapUpTime < 300
                        && Math.hypot(event.getX() - lastTapX, event.getY() - lastTapY) < dp(48f)) {
                    animateZoom(event.getX(), event.getY(), 2.0);
                    lastTapUpTime = 0;
                } else {
                    lastTapUpTime = now;
                    lastTapX = event.getX();
                    lastTapY = event.getY();
                }
            }
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            pinching = true;
            tapMoved = true;
            lastTapUpTime = 0;
            pinchStartSpan = pointerSpan(event);
            pinchStartScale = scale;
            pinchStartOffsetX = offsetX;
            pinchStartOffsetY = offsetY;
            pinchFocusX = (event.getX(0) + event.getX(1)) / 2f;
            pinchFocusY = (event.getY(0) + event.getY(1)) / 2f;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestureDetector.onTouchEvent(cancel);
            cancel.recycle();
            return true;
        }

        if (pinching && event.getPointerCount() >= 2) {
            if (action == MotionEvent.ACTION_MOVE && pinchStartSpan > 1f) {
                float span = pointerSpan(event);
                double target = clampScale(pinchStartScale * span / pinchStartSpan, true);
                double k = target / pinchStartScale;
                offsetX = pinchFocusX - (pinchFocusX - pinchStartOffsetX) * k;
                offsetY = pinchFocusY - (pinchFocusY - pinchStartOffsetY) * k;
                scale = target;
                clampPan();
                invalidate();
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                pinching = false;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pinching = false;
        }

        if (!pinching && event.getPointerCount() == 1) {
            gestureDetector.onTouchEvent(event);
        }
        return true;
    }

    private static float pointerSpan(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return 0f;
        }
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!initialized) {
            initialized = true;
            fitChina();
            if (track.size() > 0) {
                autoZoomed = true;
                fitTrack();
            }
        }
    }
}
