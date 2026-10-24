package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** HTTP for the home data: timeouts (connect 8 s, read 15 s), gzip, redirects, cancellable. Never logs URLs with keys. */
final class Net {
    private Net() {}

    private static final java.util.concurrent.atomic.AtomicInteger PART_ID = new java.util.concurrent.atomic.AtomicInteger();

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String what) {
            super(what + " returned HTTP " + code);
            this.code = code;
        }
    }

    /** Stops the requests made with it: the ones open are cut, later ones throw at once. */
    static final class Cancel {
        private final List<HttpURLConnection> open = new ArrayList<>();
        private volatile boolean cancelled;

        boolean isCancelled() { return cancelled; }

        void cancel() {
            List<HttpURLConnection> copy;
            synchronized (open) {
                cancelled = true;
                copy = new ArrayList<>(open);
            }
            for (HttpURLConnection c : copy) c.disconnect();
        }

        private void add(HttpURLConnection c) throws IOException {
            synchronized (open) {
                if (cancelled) throw new IOException("cancelled");
                open.add(c);
            }
        }

        private void remove(HttpURLConnection c) {
            synchronized (open) { open.remove(c); }
        }
    }

    /** GET; `headers` are name, value pairs. `what` names the service in errors ("TMDB"). */
    static String get(String url, String what, Cancel cancel, String... headers) throws IOException {
        return request(url, null, what, cancel, headers);
    }

    /** POST with a JSON body. */
    static String postJson(String url, String json, String what, Cancel cancel) throws IOException {
        return request(url, json, what, cancel, new String[] {"Content-Type", "application/json"});
    }

    private static String request(String url, String body, String what, Cancel cancel, String[] headers) throws IOException {
        HttpURLConnection c = open(url, body, cancel, headers);
        try (InputStream in = stream(c)) {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code, what);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (cancel != null) cancel.remove(c);
            c.disconnect();
        }
    }

    /** Downloads to `dest` (through a .part file renamed at the end). */
    static void download(String url, File dest, Cancel cancel) throws IOException {
        HttpURLConnection c = open(url, null, cancel);
        File part = new File(dest.getPath() + "." + PART_ID.incrementAndGet() + ".part");   // ours alone
        try (InputStream in = stream(c); OutputStream out = new FileOutputStream(part)) {
            if (c.getResponseCode() != 200) throw new HttpError(c.getResponseCode(), "image server");
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            if (cancel != null) cancel.remove(c);
            c.disconnect();
        }
        if (!part.renameTo(dest)) {
            boolean there = dest.exists() && dest.length() > 0;   // another download won the race
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            if (there) return;
            throw new IOException("couldn't save the file");
        }
    }

    private static HttpURLConnection open(String url, String body, Cancel cancel, String... headers) throws IOException {
        String firstHost = new URL(url).getHost();
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (cancel != null) cancel.add(c);
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(false);   // http -> https redirects are not followed by default: do it here
                c.setRequestProperty("Accept-Encoding", "gzip");
                c.setRequestProperty("Accept", "application/json, image/*");
                c.setRequestProperty("User-Agent", "FreeMyTV");
                boolean sameHost = firstHost.equalsIgnoreCase(new URL(url).getHost());   // never send Authorization elsewhere
                for (int i = 0; sameHost && i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String to = c.getHeaderField("Location");
                    if (cancel != null) cancel.remove(c);
                    c.disconnect();
                    if (to == null) throw new IOException("redirect without a place");
                    url = new URL(new URL(url), to).toString();
                    if (code == 303) body = null;
                    continue;
                }
                return c;
            } catch (IOException | RuntimeException e) {
                if (cancel != null) cancel.remove(c);
                c.disconnect();
                throw e;
            }
        }
        throw new IOException("too many redirects");
    }

    private static InputStream stream(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return new java.io.ByteArrayInputStream(new byte[0]);
        return "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new GZIPInputStream(in) : in;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
}
