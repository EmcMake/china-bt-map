package com.btmap.china.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.btmap.china.track.TrackStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线中国地图视图：省/市界、经纬网、轨迹点与连线，支持拖动、双指缩放、双击放大、长按取经纬度。
 * 地图数据来自 assets/china_map.bin（由 tools/pack_map.py 生成）。
 */
public class ChinaMapView extends View {
    public interface OnCoordinatePickListener {
        void onCoordinatePicked(double lat, double lon);
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
    private static final int COLOR_HALO = 0xFFFFFFFF;

    /** 默认视野：中国大陆 + 海南 + 台湾（南海诸岛可缩小/平移查看）。 */
    private static final double FIT_LON_MIN = 73.3;
    private static final double FIT_LON_MAX = 135.4;
    private static final double FIT_LAT_MIN = 17.0;
    private static final double FIT_LAT_MAX = 54.0;

    private final TrackStore track = TrackStore.INSTANCE;

    private MapData data;
    private String loadError;

    private final List<Path> provincePaths = new ArrayList<>();
    private final List<Path> cityPaths = new ArrayList<>();
    private Path nineDashPath;
    private final List<MapData.Feature> provinceFeatures = new ArrayList<>();
    private final List<MapData.Feature> cityFeatures = new ArrayList<>();

    private final Paint pLandFill = new Paint();
    private final Paint pProvBorder = new Paint();
    private final Paint pCityBorder = new Paint();
    private final Paint pGrid = new Paint();
    private final Paint pGridText = new Paint();
    private final Paint pNineDash = new Paint();
    private final Paint pLabel = new Paint();
    private final Paint pLabelHalo = new Paint();
    private final Paint pCityDot = new Paint();
    private final Paint pTrackLine = new Paint();
    private final Paint pTrackDot = new Paint();
    private final Paint pTrackDotStroke = new Paint();
    private final Paint pLatestOuter = new Paint();
    private final Paint pLatestInner = new Paint();
    private final Paint pBubbleBg = new Paint();
    private final Paint pBubbleText = new Paint();
    private final Paint pScaleBar = new Paint();
    private final Paint pScaleBarText = new Paint();
    private final Paint pHint = new Paint();
    private final Paint pAttribution = new Paint();
    private final Paint pCompass = new Paint();
    private final Paint pCompassText = new Paint();

    private final Path trackPath = new Path();
    private float[] trackX = new float[1024];
    private float[] trackY = new float[1024];
    private int trackSynced;
    private long trackDroppedSynced;

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
    private boolean layersEnabled = true;

    private double scaleAtStart = 1;
    private double offsetXAtStart;
    private double offsetYAtStart;
    private float focusXAtStart;
    private float focusYAtStart;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;

    private OnCoordinatePickListener pickListener;

    public ChinaMapView(Context context) {
        this(context, null);
    }

    public ChinaMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(COLOR_SEA);

        pLandFill.setStyle(Paint.Style.FILL);
        pLandFill.setColor(COLOR_LAND);
        pLandFill.setAntiAlias(true);

        pProvBorder.setStyle(Paint.Style.STROKE);
        pProvBorder.setColor(COLOR_PROV_BORDER);
        pProvBorder.setAntiAlias(true);
        pProvBorder.setStrokeJoin(Paint.Join.ROUND);

        pCityBorder.setStyle(Paint.Style.STROKE);
        pCityBorder.setColor(COLOR_CITY_BORDER);
        pCityBorder.setAntiAlias(true);

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
        pLabel.setAntiAlias(true);
        pLabel.setTextAlign(Paint.Align.CENTER);
        pLabel.setFakeBoldText(true);

        pLabelHalo.setColor(COLOR_HALO);
        pLabelHalo.setTextSize(dp(11f));
        pLabelHalo.setAntiAlias(true);
        pLabelHalo.setTextAlign(Paint.Align.CENTER);
        pLabelHalo.setStyle(Paint.Style.STROKE);
        pLabelHalo.setStrokeWidth(dp(2.6f));
        pLabelHalo.setFakeBoldText(true);

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

        pLatestOuter.setColor(0x33E53935);
        pLatestOuter.setStyle(Paint.Style.FILL);
        pLatestOuter.setAntiAlias(true);

        pLatestInner.setColor(COLOR_TRACK);
        pLatestInner.setStyle(Paint.Style.FILL);
        pLatestInner.setAntiAlias(true);

        pBubbleBg.setColor(0xE6FFFFFF);
        pBubbleBg.setStyle(Paint.Style.FILL);
        pBubbleBg.setAntiAlias(true);

        pBubbleText.setColor(0xFF212121);
        pBubbleText.setTextSize(dp(11f));
        pBubbleText.setAntiAlias(true);
        pBubbleText.setTextAlign(Paint.Align.CENTER);

        pScaleBar.setColor(0xFF37474F);
        pScaleBar.setStyle(Paint.Style.STROKE);
        pScaleBar.setStrokeWidth(dp(1.4f));
        pScaleBar.setAntiAlias(true);

        pScaleBarText.setColor(0xFF37474F);
        pScaleBarText.setTextSize(dp(10f));
        pScaleBarText.setAntiAlias(true);

        pHint.setColor(0xFF607D8B);
        pHint.setTextSize(dp(13f));
        pHint.setAntiAlias(true);
        pHint.setTextAlign(Paint.Align.CENTER);

        pAttribution.setColor(0x88607D8B);
        pAttribution.setTextSize(dp(8.5f));
        pAttribution.setAntiAlias(true);
        pAttribution.setTextAlign(Paint.Align.RIGHT);

        pCompass.setColor(0xAA37474F);
        pCompass.setStyle(Paint.Style.FILL);
        pCompass.setAntiAlias(true);

        pCompassText.setColor(0xCC37474F);
        pCompassText.setTextSize(dp(10f));
        pCompassText.setAntiAlias(true);
        pCompassText.setTextAlign(Paint.Align.CENTER);
        pCompassText.setFakeBoldText(true);

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                offsetX -= dx;
                offsetY -= dy;
                clampPan();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(e.getX(), e.getY(), 2.0);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
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

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaleAtStart = scale;
                offsetXAtStart = offsetX;
                offsetYAtStart = offsetY;
                focusXAtStart = detector.getFocusX();
                focusYAtStart = detector.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                double target = clampScale(scaleAtStart * detector.getScaleFactor());
                double k = target / scaleAtStart;
                offsetX = focusXAtStart - (focusXAtStart - offsetXAtStart) * k;
                offsetY = focusYAtStart - (focusYAtStart - offsetYAtStart) * k;
                scale = target;
                clampPan();
                invalidate();
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
            Path p = new Path();
            for (int r = 0; r < f.ringStart.length; r++) {
                int start = f.ringStart[r];
                int len = f.ringLen[r];
                if (len < 2) {
                    continue;
                }
                p.moveTo(f.xs[start], f.ys[start]);
                for (int i = 1; i < len; i++) {
                    p.lineTo(f.xs[start + i], f.ys[start + i]);
                }
                if (f.level != MapData.LEVEL_NINEDASH) {
                    p.close();
                }
            }
            if (f.level == MapData.LEVEL_PROVINCE) {
                provincePaths.add(p);
                provinceFeatures.add(f);
            } else if (f.level == MapData.LEVEL_CITY) {
                cityPaths.add(p);
                cityFeatures.add(f);
            } else {
                nineDashPath = p;
            }
        }
    }

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

    /** 建筑/市界图层开关（菜单里用，默认开启）。 */
    public void setDetailLayersEnabled(boolean v) {
        layersEnabled = v;
        invalidate();
    }

    // ------------------------------------------------------------ 视图变换
    public void fitChina() {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        double x0 = Mercator.lonToX(FIT_LON_MIN);
        double x1 = Mercator.lonToX(FIT_LON_MAX);
        double y0 = Mercator.latToY(FIT_LAT_MAX);
        double y1 = Mercator.latToY(FIT_LAT_MIN);
        fitScale = Math.min(getWidth() / (x1 - x0), getHeight() / (y1 - y0)) * 0.94;
        minScale = fitScale * 0.6;
        maxScale = fitScale * 160;
        scale = fitScale;
        offsetX = getWidth() / 2.0 - (x0 + x1) / 2.0 * scale;
        offsetY = getHeight() / 2.0 - (y0 + y1) / 2.0 * scale;
        invalidate();
    }

    public void zoomBy(double factor) {
        zoomAt(getWidth() / 2.0f, getHeight() / 2.0f, factor);
    }

    private void zoomAt(float fx, float fy, double factor) {
        double target = clampScale(scale * factor);
        double k = target / scale;
        offsetX = fx - (fx - offsetX) * k;
        offsetY = fy - (fy - offsetY) * k;
        scale = target;
        clampPan();
        invalidate();
    }

    private double clampScale(double s) {
        return Math.max(minScale, Math.min(maxScale, s));
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
        double[] ll = track.lastLatLon();
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
            scale = fitScale * 20;
            offsetX = getWidth() / 2.0 - wx * scale;
            offsetY = getHeight() / 2.0 - wy * scale;
        }
        clampPan();
        invalidate();
    }

    // ------------------------------------------------------------ 轨迹同步
    /** 把当前轨迹完整地放进视野（首点定位/演示数据用）。 */
    public void fitTrack() {
        int n = track.size();
        if (n == 0 || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        if (n == 1) {
            scale = clampScale(fitScale * 60);
            centerOnLatest();
            clampPan();
            invalidate();
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float x = (float) Mercator.lonToX(track.lon(i));
            float y = (float) Mercator.latToY(track.lat(i));
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        double w = Math.max(1e-9, maxX - minX);
        double h = Math.max(1e-9, maxY - minY);
        double target = Math.min(getWidth() * 0.80 / w, getHeight() * 0.70 / h);
        scale = clampScale(Math.min(target, fitScale * 120));
        offsetX = getWidth() / 2.0 - (minX + maxX) / 2.0 * scale;
        offsetY = getHeight() / 2.0 - (minY + maxY) / 2.0 * scale;
        clampPan();
        invalidate();
    }

    /** 轨迹变化（主线程调用或自行转发）。 */
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
        int n = track.size();
        long dropped = track.droppedCount();
        if (dropped != trackDroppedSynced) {
            // 存储里丢了最旧的点，整条轨迹重建
            trackDroppedSynced = dropped;
            trackSynced = 0;
            trackPath.reset();
        }
        if (n < trackSynced) {
            trackSynced = 0;
            trackPath.reset();
        }
        for (int i = trackSynced; i < n; i++) {
            float x = (float) Mercator.lonToX(track.lon(i));
            float y = (float) Mercator.latToY(track.lat(i));
            if (i == 0) {
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
        }
    }

    private void clearTrack() {
        track.clear();
        trackSynced = 0;
        trackDroppedSynced = 0;
        trackPath.reset();
        autoZoomed = false;
        invalidate();
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
        syncTrack();
        float s = (float) scale;

        canvas.save();
        canvas.translate((float) offsetX, (float) offsetY);
        canvas.scale(s, s);

        for (Path p : provincePaths) {
            canvas.drawPath(p, pLandFill);
        }

        pGrid.setStrokeWidth(dp(0.7f) / s);
        drawGraticule(canvas);

        pProvBorder.setStrokeWidth(dp(1.1f) / s);
        for (Path p : provincePaths) {
            canvas.drawPath(p, pProvBorder);
        }

        if (layersEnabled && scale > fitScale * 2.2) {
            pCityBorder.setStrokeWidth(dp(0.7f) / s);
            for (Path p : cityPaths) {
                canvas.drawPath(p, pCityBorder);
            }
        }

        if (nineDashPath != null) {
            pNineDash.setStrokeWidth(dp(1.8f) / s);
            canvas.drawPath(nineDashPath, pNineDash);
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

        drawGridLabels(canvas);
        drawLabels(canvas);
        drawLatest(canvas);
        drawHint(canvas);
        drawScaleBar(canvas);
        drawCompass(canvas);
        drawAttribution(canvas);
    }

    private double gridStep() {
        double pxPerDeg = scale / 360.0;
        // 从小到大挑“第一个间距 >= 90px”的步长，保证网格不会太稀
        double[] steps = {0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 30};
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
            if (x < dp(24) || x > getWidth() - dp(24)) {
                continue;
            }
            String text = String.format(Locale.US, "%.2f°E", lon);
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
            String text = String.format(Locale.US, "%.2f°N", lat);
            canvas.drawText(text, dp(6f), y - dp(2f), pGridText);
        }
    }

    private void drawLabels(Canvas canvas) {
        if (scale > fitScale * 1.6) {
            float r = dp(2.2f);
            for (MapData.Feature f : cityFeatures) {
                if (!f.hasCenter()) {
                    continue;
                }
                float[] pt = lonLatToScreen(f.centerLat, f.centerLon);
                if (pt[0] < -dp(20) || pt[0] > getWidth() + dp(20)
                        || pt[1] < -dp(20) || pt[1] > getHeight() + dp(20)) {
                    continue;
                }
                canvas.drawCircle(pt[0], pt[1], r, pCityDot);
            }
        }
        for (MapData.Feature f : provinceFeatures) {
            if (!f.hasCenter()) {
                continue;
            }
            float[] pt = lonLatToScreen(f.centerLat, f.centerLon);
            if (pt[0] < 0 || pt[0] > getWidth() || pt[1] < 0 || pt[1] > getHeight()) {
                continue;
            }
            String label = shortName(f.name);
            if (scale < fitScale * 1.3 && !isMajorProvince(label)) {
                continue;
            }
            pLabelHalo.setTextSize(dp(11f));
            pLabel.setTextSize(dp(11f));
            canvas.drawText(label, pt[0], pt[1], pLabelHalo);
            canvas.drawText(label, pt[0], pt[1], pLabel);
        }
        if (scale > fitScale * 3.5) {
            pLabelHalo.setTextSize(dp(9.5f));
            pLabel.setTextSize(dp(9.5f));
            pLabel.setFakeBoldText(false);
            pLabelHalo.setFakeBoldText(false);
            pLabel.setColor(0xFF6D5B45);
            int drawn = 0;
            for (MapData.Feature f : cityFeatures) {
                if (!f.hasCenter() || f.name.isEmpty()) {
                    continue;
                }
                float[] pt = lonLatToScreen(f.centerLat, f.centerLon);
                if (pt[0] < dp(10) || pt[0] > getWidth() - dp(10)
                        || pt[1] < dp(10) || pt[1] > getHeight() - dp(10)) {
                    continue;
                }
                canvas.drawText(f.name, pt[0], pt[1] - dp(5f), pLabelHalo);
                canvas.drawText(f.name, pt[0], pt[1] - dp(5f), pLabel);
                if (++drawn > 120) {
                    break;
                }
            }
            pLabel.setFakeBoldText(true);
            pLabelHalo.setFakeBoldText(true);
            pLabel.setColor(COLOR_LABEL);
        }
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

    private void drawLatest(Canvas canvas) {
        if (trackSynced == 0) {
            return;
        }
        double lat = track.lat(trackSynced - 1);
        double lon = track.lon(trackSynced - 1);
        float[] pt = lonLatToScreen(lat, lon);
        float cx = pt[0], cy = pt[1];

        canvas.drawCircle(cx, cy, dp(14f), pLatestOuter);
        canvas.drawCircle(cx, cy, dp(6f), pLatestInner);
        canvas.drawCircle(cx, cy, dp(6f), pTrackDot);
        canvas.drawCircle(cx, cy, dp(3f), pLatestInner);

        String text = String.format(Locale.US, "%.6f, %.6f", lat, lon);
        float tw = pBubbleText.measureText(text);
        float pad = dp(7f);
        float bw = tw + pad * 2;
        float bh = dp(22f);
        float bx = Math.max(dp(4), Math.min(getWidth() - bw - dp(4), cx - bw / 2));
        float by = cy - dp(16f) - bh;
        if (by < dp(4)) {
            by = cy + dp(16f);
        }
        RectF rect = new RectF(bx, by, bx + bw, by + bh);
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pBubbleBg);
        canvas.drawText(text, bx + bw / 2, by + bh / 2 + pBubbleText.getTextSize() * 0.36f, pBubbleText);
    }

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
        float y0 = getHeight() - dp(104f);
        float x1 = x0 + len;
        Path bar = new Path();
        bar.moveTo(x0, y0);
        bar.lineTo(x1, y0);
        bar.moveTo(x0, y0 - dp(4f));
        bar.lineTo(x0, y0 + dp(4f));
        bar.moveTo(x1, y0 - dp(4f));
        bar.lineTo(x1, y0 + dp(4f));
        canvas.drawPath(bar, pScaleBar);
        String label = nice >= 1000
                ? String.format(Locale.US, "%.0f km", nice / 1000)
                : String.format(Locale.US, "%.0f m", nice);
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
        canvas.drawText("地图数据 © DataV.GeoAtlas", getWidth() - dp(8f), getHeight() - dp(96f), pAttribution);
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (!scaleDetector.isInProgress()) {
            gestureDetector.onTouchEvent(event);
        }
        return true;
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
