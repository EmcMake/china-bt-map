package com.btmap.china.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在线栅格瓦片层：底图 + 透明叠加层（路网/地名）。 */
public final class RasterTileLayer {
    private static final int MAX_TILES_PER_FRAME = 320;
    private static final long FAIL_BACKOFF_MS = 30_000;

    private final TileCache cache;
    private final View target;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private List<TileSource> sources = new ArrayList<>();
    private boolean lastEmpty = true;

    public RasterTileLayer(Context context, View target) {
        this.target = target;
        this.cache = new TileCache(context);
    }

    public void setSources(TileSource base, TileSource... overlays) {
        List<TileSource> list = new ArrayList<>();
        if (base != null) {
            list.add(base);
        }
        if (overlays != null) {
            for (TileSource o : overlays) {
                if (o != null) {
                    list.add(o);
                }
            }
        }
        sources = list;
        lastEmpty = true;
        target.postInvalidateOnAnimation();
    }

    public TileSource base() {
        return sources.isEmpty() ? null : sources.get(0);
    }

    public boolean lastFrameEmpty() {
        return lastEmpty;
    }

    public long cacheBytes() {
        return cache.diskSize();
    }

    public void clearCache() {
        cache.clear();
        target.postInvalidateOnAnimation();
    }

    public void draw(Canvas canvas, double scale, double offsetX, double offsetY,
                     int viewW, int viewH) {
        if (sources.isEmpty() || scale <= 0) {
            lastEmpty = true;
            return;
        }
        boolean any = false;
        int drawnTiles = 0;
        for (int si = 0; si < sources.size(); si++) {
            TileSource src = sources.get(si);
            boolean base = si == 0;
            int z = TileMath.zoomForScale(scale, src.minZoom, src.maxZoom);
            int n = TileMath.tileCount(z);
            double tw = 1.0 / n;

            double wx0 = (0 - offsetX) / scale;
            double wy0 = (0 - offsetY) / scale;
            double wx1 = (viewW - offsetX) / scale;
            double wy1 = (viewH - offsetY) / scale;

            int x0 = (int) Math.floor(wx0 * n) - 1;
            int x1 = (int) Math.ceil(wx1 * n) + 1;
            int y0 = (int) Math.floor(wy0 * n) - 1;
            int y1 = (int) Math.ceil(wy1 * n) + 1;

            for (int x = x0; x <= x1; x++) {
                int cx = ((x % n) + n) % n;
                for (int y = y0; y <= y1; y++) {
                    if (drawnTiles++ > MAX_TILES_PER_FRAME) {
                        continue;
                    }
                    int cy = Math.max(0, Math.min(n - 1, y));
                    Bitmap b = cache.get(src.cacheId(), z, cx, cy);
                    if (b != null) {
                        drawTile(canvas, b, x, y, tw, scale, offsetX, offsetY);
                        any = true;
                    } else {
                        if (base && x == cx && y == cy) {
                            Bitmap parent = drawParent(canvas, src, z, cx, cy, scale,
                                    offsetX, offsetY);
                            if (parent != null) {
                                any = true;
                            }
                        }
                        request(src, z, cx, cy);
                    }
                }
            }
        }
        lastEmpty = !any;
    }

    private void drawTile(Canvas canvas, Bitmap b, int x, int y, double tw,
                          double scale, double offsetX, double offsetY) {
        float l = (float) (x * tw * scale + offsetX);
        float t = (float) (y * tw * scale + offsetY);
        float r = (float) ((x + 1) * tw * scale + offsetX);
        float bo = (float) ((y + 1) * tw * scale + offsetY);
        // 多画 1px，避免相邻瓦片之间出现缝隙
        canvas.drawBitmap(b, null, new RectF(l, t, r + 1, bo + 1), paint);
    }

    /** 未加载时用上一级瓦片放大占位，避免整屏空白。 */
    private Bitmap drawParent(Canvas canvas, TileSource src, int z, int x, int y,
                              double scale, double offsetX, double offsetY) {
        for (int dz = 1; dz <= 3; dz++) {
            int pz = z - dz;
            if (pz < src.minZoom) {
                return null;
            }
            int sub = 1 << dz;
            int px = x >> dz;
            int py = y >> dz;
            Bitmap b = cache.get(src.cacheId(), pz, px, py);
            if (b == null) {
                continue;
            }
            double pw = 1.0 / TileMath.tileCount(pz);
            float left = (float) (px * pw * scale + offsetX);
            float top = (float) (py * pw * scale + offsetY);
            float size = (float) (pw * scale);
            float fx = (x - px * sub) / (float) sub;
            float fy = (y - py * sub) / (float) sub;
            float fs = 1f / sub;
            Rect srcRect = new Rect(
                    (int) (b.getWidth() * fx),
                    (int) (b.getHeight() * fy),
                    (int) (b.getWidth() * (fx + fs)),
                    (int) (b.getHeight() * (fy + fs)));
            RectF dst = new RectF(left + fx * size, top + fy * size,
                    left + (fx + fs) * size + 1, top + (fy + fs) * size + 1);
            canvas.drawBitmap(b, srcRect, dst, paint);
            return b;
        }
        return null;
    }

    private void request(final TileSource src, final int z, final int x, final int y) {
        final String key = src.cacheId() + "/" + z + "/" + x + "/" + y;
        Long failAt = failed.get(key);
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_BACKOFF_MS) {
            return;
        }
        if (!pending.add(key)) {
            return;
        }
        final String url = src.url(z, x, y, keyFor(src));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "ChinaBtMap/1.2 (Android)");
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = conn.getInputStream()) {
                            bmp = BitmapFactory.decodeStream(in);
                        }
                    }
                } catch (Throwable ignored) {
                    bmp = null;
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                    pending.remove(key);
                }
                if (bmp != null) {
                    failed.remove(key);
                    cache.put(src.cacheId(), z, x, y, bmp, src.transparent);
                } else {
                    failed.put(key, System.currentTimeMillis());
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        target.postInvalidateOnAnimation();
                    }
                });
            }
        });
    }

    /**
     * 天地图 tk 放在 key 里：setSources 前由调用方通过 {@link #setKey(String)} 设置。
     */
    private String tiandituKey = "";

    public void setKey(String key) {
        tiandituKey = key == null ? "" : key.trim();
    }

    private String keyFor(TileSource src) {
        return src.provider == TileSource.PROVIDER_TIANDITU ? tiandituKey : "";
    }
}
