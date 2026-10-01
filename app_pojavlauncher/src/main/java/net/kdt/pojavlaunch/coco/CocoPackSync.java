package net.kdt.pojavlaunch.coco;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps an instance folder identical to the Coco Lite manifest (same format as the PC
 * launcher's): "mirror" folders are made exactly equal, "seed" files are only written
 * when the player doesn't have them yet, so their own settings survive updates.
 * Only changed files are downloaded; sha1s of local files are cached by size+mtime so
 * a launch with nothing new doesn't re-hash ~400 MB.
 */
public class CocoPackSync {
    private static final String TAG = "CocoPackSync";
    private static final String HASH_CACHE = ".coco-hash-cache.json";
    private static final int THREADS = 4;
    private static final int RETRIES = 4;

    public interface Listener {
        /** Called from worker threads. */
        void onProgress(long bytesDone, long bytesTotal, int filesDone, int filesTotal);
    }

    private static final class Entry {
        String path;
        String downloadName;
        String sha1;
        long size;
        boolean mirror;
    }

    private static final class CachedHash {
        long size;
        long mtime;
        String sha1;
    }

    private final File mGameDir;
    private final Listener mListener;
    private Map<String, CachedHash> mHashCache = new HashMap<>();

    public CocoPackSync(File gameDir, Listener listener) {
        mGameDir = gameDir;
        mListener = listener;
    }

    /** Blocking. Throws when the manifest can't be read or a file can't be downloaded. */
    public void run() throws IOException, InterruptedException {
        List<Entry> entries = readManifest(DownloadUtils.downloadString(CocoConfig.PACK_BASE_URL + "/" + CocoConfig.PACK_MANIFEST));
        loadHashCache();

        List<Entry> todo = new ArrayList<>();
        long totalBytes = 0;
        Set<String> wanted = new HashSet<>();
        for (Entry e : entries) {
            wanted.add(e.path);
            File target = new File(mGameDir, e.path);
            if (!e.mirror && target.isFile()) continue; // seed: never overwrite the player's copy
            if (target.isFile() && target.length() == e.size && e.sha1.equalsIgnoreCase(localSha1(e.path, target))) continue;
            todo.add(e);
            totalBytes += e.size;
        }
        deleteExtraMirrorFiles(wanted);

        mListener.onProgress(0, totalBytes, 0, todo.size());
        if (!todo.isEmpty()) downloadAll(todo, totalBytes);
        saveHashCache();
    }

    private static List<Entry> readManifest(String json) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            JsonArray files = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("files");
            for (JsonElement el : files) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry();
                e.path = o.get("path").getAsString();
                e.downloadName = o.get("downloadName").getAsString();
                e.sha1 = o.get("sha1").getAsString();
                e.size = o.get("size").getAsLong();
                e.mirror = "mirror".equals(o.get("mode").getAsString());
                if (e.path.contains("..") || e.path.startsWith("/")) throw new IOException("Bad path in manifest: " + e.path);
                out.add(e);
            }
        } catch (RuntimeException e) {
            throw new IOException("Manifest không hợp lệ", e);
        }
        return out;
    }

    /** Files in a mirror folder that the manifest no longer lists are removed (old mod versions). */
    private void deleteExtraMirrorFiles(Set<String> wanted) {
        for (String folder : CocoConfig.MIRROR_FOLDERS) {
            File[] files = new File(mGameDir, folder).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String rel = folder + "/" + f.getName();
                if (!wanted.contains(rel) && f.delete()) {
                    Log.i(TAG, "Removed " + rel);
                    mHashCache.remove(rel);
                }
            }
        }
    }

    private void downloadAll(List<Entry> todo, long totalBytes) throws IOException, InterruptedException {
        AtomicLong bytesDone = new AtomicLong();
        AtomicInteger filesDone = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Entry e : todo) {
                futures.add(pool.submit(() -> {
                    downloadWithRetry(e, bytesDone, totalBytes, filesDone, todo.size());
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    throw new IOException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void downloadWithRetry(Entry e, AtomicLong bytesDone, long totalBytes, AtomicInteger filesDone, int filesTotal) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            try {
                downloadOne(e, bytesDone, totalBytes, filesDone.get(), filesTotal);
                mListener.onProgress(bytesDone.get(), totalBytes, filesDone.incrementAndGet(), filesTotal);
                return;
            } catch (IOException ex) {
                last = ex;
                if (ex instanceof CountedIOException) bytesDone.addAndGet(-((CountedIOException) ex).counted);
                Log.w(TAG, "Download failed (" + attempt + "/" + RETRIES + "): " + e.path, ex);
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { throw new IOException(ie); }
            }
        }
        throw new IOException("Không tải được " + e.path, last);
    }

    /** On failure the bytes it already added to bytesDone travel in a CountedIOException. */
    private void downloadOne(Entry e, AtomicLong bytesDone, long totalBytes, int filesDone, int filesTotal) throws IOException {
        File target = new File(mGameDir, e.path);
        File tmp = new File(target.getPath() + ".download");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);

        URL url = new URL(new URL(CocoConfig.PACK_BASE_URL + "/"), e.downloadName);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", Tools.APP_NAME);
        long counted = 0;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            long lastReport = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sha1.update(buf, 0, n);
                    counted += n;
                    long now = bytesDone.addAndGet(n);
                    if (now - lastReport > 256 * 1024) {
                        lastReport = now;
                        mListener.onProgress(now, totalBytes, filesDone, filesTotal);
                    }
                }
            }
            String got = toHex(sha1.digest());
            if (!got.equalsIgnoreCase(e.sha1)) throw new IOException("Sai sha1 " + e.path);
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
            if (!tmp.renameTo(target)) throw new IOException("Cannot move " + tmp);
            rememberHash(e.path, target, got);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        } catch (IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new CountedIOException(ex, counted);
        } finally {
            conn.disconnect();
        }
    }

    /** Lets downloadWithRetry roll back the bytes a failed attempt already counted. */
    private static final class CountedIOException extends IOException {
        final long counted;
        CountedIOException(IOException cause, long counted) { super(cause.getMessage(), cause); this.counted = counted; }
    }

    private String localSha1(String rel, File f) {
        CachedHash c;
        synchronized (this) { c = mHashCache.get(rel); }
        if (c != null && c.size == f.length() && c.mtime == f.lastModified()) return c.sha1;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) sha1.update(buf, 0, n);
            String hex = toHex(sha1.digest());
            rememberHash(rel, f, hex);
            return hex;
        } catch (Exception e) {
            return "";
        }
    }

    private synchronized void rememberHash(String rel, File f, String sha1) {
        CachedHash c = new CachedHash();
        c.size = f.length();
        c.mtime = f.lastModified();
        c.sha1 = sha1;
        mHashCache.put(rel, c);
    }

    private void loadHashCache() {
        File f = new File(mGameDir, HASH_CACHE);
        if (!f.isFile()) return;
        try {
            Map<String, CachedHash> m = Tools.GLOBAL_GSON.fromJson(Tools.read(f.getAbsolutePath()), new TypeToken<Map<String, CachedHash>>(){}.getType());
            if (m != null) mHashCache = new HashMap<>(m);
        } catch (Exception e) {
            Log.w(TAG, "Hash cache unreadable, rehashing", e);
        }
    }

    private synchronized void saveHashCache() {
        try {
            Tools.write(new File(mGameDir, HASH_CACHE).getAbsolutePath(), Tools.GLOBAL_GSON.toJson(mHashCache));
        } catch (IOException e) {
            Log.w(TAG, "Could not save hash cache", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
