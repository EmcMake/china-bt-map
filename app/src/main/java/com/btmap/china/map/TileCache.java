package com.btmap.china.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 栅格瓦片的内存 + 磁盘缓存。 */
public final class TileCache {
    private static final long MAX_DISK_BYTES = 200L * 1024 * 1024;
    private static final long PRUNE_TO_BYTES = 180L * 1024 * 1024;

    private final File root;
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private int writes;

    public TileCache(Context context) {
        root = new File(context.getCacheDir(), "tiles");
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(Math.max(16 * 1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private static String key(String sourceId, int z, int x, int y) {
        return sourceId + "/" + z + "/" + x + "/" + y;
    }

    public File file(String sourceId, int z, int x, int y) {
        return new File(new File(new File(root, sourceId), Integer.toString(z)),
                x + "_" + y + ".png");
    }

    /** 只查内存，供绘制线程使用；绝不做磁盘 IO。 */
    public Bitmap getMemory(String sourceId, int z, int x, int y) {
        return memory.get(key(sourceId, z, x, y));
    }

    /** 后台线程从磁盘读取并放入内存；绘制线程不要调用。 */
    public Bitmap loadFromDisk(String sourceId, int z, int x, int y) {
        String k = key(sourceId, z, x, y);
        Bitmap b = memory.get(k);
        if (b != null) {
            return b;
        }
        File f = file(sourceId, z, x, y);
        if (!f.isFile() || f.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable ignored) {
            b = null;
        }
        if (b != null) {
            memory.put(k, b);
        }
        return b;
    }

    public void put(String sourceId, int z, int x, int y, Bitmap bmp) {
        put(sourceId, z, x, y, bmp, true);
    }

    /** 先放内存立即可用，磁盘压缩/写入异步进行。 */
    public void put(String sourceId, int z, int x, int y, Bitmap bmp, boolean keepAlpha) {
        putMemory(sourceId, z, x, y, bmp);
        final String sid = sourceId;
        final int tz = z, tx = x, ty = y;
        final boolean alpha = keepAlpha;
        diskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                persist(sid, tz, tx, ty, bmp, alpha);
            }
        });
    }

    public void putMemory(String sourceId, int z, int x, int y, Bitmap bmp) {
        memory.put(key(sourceId, z, x, y), bmp);
    }

    private void persist(String sourceId, int z, int x, int y, Bitmap bmp, boolean keepAlpha) {
        File f = file(sourceId, z, x, y);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (keepAlpha) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
        } catch (Throwable ignored) {
            f.delete();
        }
        if (++writes % 128 == 0) {
            prune();
        }
    }

    public long diskSize() {
        return sizeOf(root);
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File c : fs) {
                n += sizeOf(c);
            }
        }
        return n;
    }

    /** 超出上限时按最后修改时间删除最旧的瓦片。 */
    public void prune() {
        File[] fs = root.listFiles();
        if (fs == null) {
            return;
        }
        List<File> files = new ArrayList<>();
        collect(fs, files);
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= MAX_DISK_BYTES) {
            return;
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= PRUNE_TO_BYTES) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
            }
        }
    }

    private static void collect(File[] fs, List<File> out) {
        for (File f : fs) {
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) {
                    collect(cs, out);
                }
            } else if (f.getName().endsWith(".png")) {
                out.add(f);
            }
        }
    }

    public void clear() {
        memory.evictAll();
        delete(root);
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) {
                    delete(c);
                }
            }
        }
        f.delete();
    }
}
