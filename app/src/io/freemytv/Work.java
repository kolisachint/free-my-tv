package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The home data's background threads, shared by Recs, Search, Stremio and History (no new thread per call):
 * `io` runs a job (2 threads), `all` fans a job's requests out to 4 more threads (so a job waiting for its
 * requests never starves them). Idle threads exit after 20 s. Images has its own two threads.
 */
final class Work {
    private Work() {}

    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ThreadPoolExecutor pool(int n, String name) {
        ThreadFactory f = r -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, name);
        ThreadPoolExecutor e = new ThreadPoolExecutor(n, n, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), f);
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    private static final ThreadPoolExecutor IO = pool(2, "fmtv-io"), FAN = pool(4, "fmtv-fan");

    /** Runs a job in the background. */
    static void io(Runnable job) {
        IO.execute(job);
    }

    /** Runs the tasks at most 4 at a time and returns when all are done (call it from `io` jobs, never the main thread). */
    static void all(List<Runnable> tasks) {
        if (tasks.isEmpty()) return;
        CountDownLatch done = new CountDownLatch(tasks.size());
        for (Runnable t : tasks) {
            FAN.execute(() -> {
                try {
                    t.run();
                } catch (RuntimeException e) {
                    AppLog.w("task failed: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void main(Runnable job) {
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else MAIN.post(job);
    }
}
