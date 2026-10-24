package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Loads pictures for the home: two background threads (the newest request first: when focus moves on, what you
 * are looking at now loads before what you passed), decoded small (sampled, then scaled to the size asked, RGB_565
 * for JPEGs), an in-memory LruCache of min(16 MB, maxMemory / 8) and a disk cache of the downloaded files in
 * cacheDir/img (40 MB, oldest pruned). Requests are cancelled by tag. Callbacks run on the main thread.
 *
 * Windowed loading is the caller's job: ask for the cards near the focus, cancel(tag) for the ones that left, and
 * draw a placeholder for the rest.
 */
final class Images {
    private Images() {}

    interface Callback {
        /** `bitmap` is null when the picture couldn't be loaded. Never recycle it: it may be in the cache. */
        void onImage(String url, Bitmap bitmap);
    }

    private static final long DISK_MAX = 40L * 1024 * 1024;
    private static final int PRUNE_EVERY = 20;

    private static final class Request implements Runnable {
        final String url, key;
        final int w, h;
        final Object tag;
        final Callback cb;
        volatile boolean cancelled;
        volatile Net.Cancel net;

        Request(String url, int w, int h, Object tag, Callback cb) {
            this.url = url;
            this.w = w;
            this.h = h;
            this.tag = tag;
            this.cb = cb;
            this.key = url + "@" + w + "x" + h;
        }

        @Override public void run() {
            if (cancelled) return;
            Bitmap b = null;
            try {
                b = load(this);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (!cancelled) AppLog.w("image: " + e.getClass().getSimpleName());
            }
            final Bitmap result = b;
            if (result != null) memory().put(key, result);
            if (cancelled) return;
            Work.MAIN.post(() -> {
                forget(this);
                if (!cancelled) cb.onImage(url, result);
            });
        }
    }

    private static LruCache<String, Bitmap> memory;
    private static ThreadPoolExecutor pool;
    private static File dir;
    private static int writes;
    private static final Map<Object, List<Request>> PENDING = new HashMap<>();

    private static synchronized LruCache<String, Bitmap> memory() {
        if (memory == null) {
            long max = Math.min(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
            memory = new LruCache<String, Bitmap>((int) max) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };
        }
        return memory;
    }

    private static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            // offer() puts at the front: a LIFO queue, so the newest request runs first.
            LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<Runnable>() {
                @Override public boolean offer(Runnable r) { return offerFirst(r); }
            };
            pool = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, queue, r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "fmtv-img"));
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    private static synchronized File dir() {
        if (dir == null) {
            dir = new File(Prefs.context().getCacheDir(), "img");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Memory cache: pictures held and their size in bytes (the debug overlay). */
    static int cacheCount() { return memory().size() == 0 ? 0 : memory().snapshot().size(); }

    static long cacheBytes() { return memory().size(); }

    /** The picture if it is already in memory at that size, else null. Main thread; no I/O. */
    static Bitmap peek(String url, int w, int h) {
        return memory().get(url + "@" + w + "x" + h);
    }

    /**
     * Loads `url` scaled so it covers w × h pixels (never larger than needed; aspect ratio kept; the caller crops).
     * Answers at once (on the calling thread, which must be the main one) when it is in memory. `tag` groups requests
     * for cancel(): use the card, row or screen they belong to. Returns false only when url is empty.
     */
    static boolean get(String url, int w, int h, Object tag, Callback cb) {
        if (url == null || url.isEmpty() || w <= 0 || h <= 0) return false;
        Bitmap hit = peek(url, w, h);
        if (hit != null) {
            cb.onImage(url, hit);
            return true;
        }
        Request r = new Request(url, w, h, tag, cb);
        synchronized (PENDING) {
            List<Request> list = PENDING.get(tag);
            if (list == null) PENDING.put(tag, list = new ArrayList<>());
            list.add(r);
        }
        pool().execute(r);
        return true;
    }

    /** Drops the requests made with `tag`: queued ones never run, running ones are cut and their callbacks never called. */
    static void cancel(Object tag) {
        List<Request> list;
        synchronized (PENDING) {
            list = PENDING.remove(tag);
        }
        if (list == null) return;
        for (Request r : list) {
            r.cancelled = true;
            pool().remove(r);
            Net.Cancel n = r.net;
            if (n != null) n.cancel();
        }
    }

    private static void forget(Request r) {
        synchronized (PENDING) {
            List<Request> list = PENDING.get(r.tag);
            if (list != null) {
                list.remove(r);
                if (list.isEmpty()) PENDING.remove(r.tag);
            }
        }
    }

    /** onTrimMemory / low memory: empties the memory cache and drops queued requests (the disk cache stays). */
    static void trim() {
        memory().evictAll();
        List<Object> tags;
        synchronized (PENDING) {
            tags = new ArrayList<>(PENDING.keySet());
        }
        for (Object tag : tags) cancel(tag);
    }

    // MARK: - Loading

    private static Bitmap load(Request r) throws IOException {
        File f = new File(dir(), name(r.url));
        if (!f.exists() || f.length() == 0) {
            r.net = new Net.Cancel();
            Net.download(r.url, f, r.net);
            if (r.cancelled) return null;
            prune();
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // oldest-used goes first when the cache is full
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();   // not a picture (an error page, a cut download)
            throw new IOException("unreadable picture");
        }
        // Cover w × h: scale by the larger ratio, and never enlarge.
        float scale = Math.min(1f, Math.max(r.w / (float) bounds.outWidth, r.h / (float) bounds.outHeight));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample(scale);
        boolean opaque = !"image/png".equals(bounds.outMimeType) && !"image/webp".equals(bounds.outMimeType);
        o.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("unreadable picture");
        int tw = Math.round(bounds.outWidth * scale), th = Math.round(bounds.outHeight * scale);
        if (r.cancelled) return null;
        if (b.getWidth() > tw * 1.1f && tw > 0 && th > 0) {
            Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    /** The largest power of two that keeps the decoded picture at least as large as the size wanted. */
    static int sample(float scale) {
        int n = 1;
        while (scale <= 1f / (n * 2)) n *= 2;
        return n;
    }

    private static String name(String url) {
        return Integer.toHexString(url.hashCode()) + Integer.toHexString(new StringBuilder(url).reverse().toString().hashCode());
    }

    private static void prune() {
        if (++writes % PRUNE_EVERY != 1) return;
        File[] files = dir().listFiles();
        if (files == null) return;
        long stale = System.currentTimeMillis() - 10 * 60_000L, total = 0;
        List<File> keep = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".part")) {
                //noinspection ResultOfMethodCallIgnored
                if (f.lastModified() < stale) f.delete();   // a download cut off when the app stopped
                continue;
            }
            total += f.length();
            keep.add(f);
        }
        if (total <= DISK_MAX) return;
        Collections.sort(keep, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : keep) {
            if (total <= DISK_MAX * 3 / 4) break;   // down to 30 MB: not a prune on every write
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
