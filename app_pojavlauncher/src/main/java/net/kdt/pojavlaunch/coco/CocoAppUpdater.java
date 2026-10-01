package net.kdt.pojavlaunch.coco;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update for the launcher APK (mods update themselves through CocoPackSync).
 * android-version.json in release "android-app" says which versionCode is current; when it is
 * newer than ours the APK for this device's ABI is downloaded and handed to PackageInstaller.
 * Android always asks the player to confirm; app data and the downloaded pack are kept.
 */
public final class CocoAppUpdater {
    private static final String TAG = "CocoAppUpdater";
    public static final String VERSION_URL = "https://github.com/Coconubs/cocolauncher-pack/releases/download/android-app/android-version.json";
    private static final String APK_BASE_URL = "https://github.com/Coconubs/cocolauncher-pack/releases/download/android-app/";
    static final String ACTION_INSTALL_STATUS = "com.coconubs.cocolauncher.INSTALL_STATUS";

    private static boolean sCheckedThisRun;
    /** Set once a newer version is known: the update is mandatory, PLAY stays blocked until installed. */
    private static volatile String sPendingLabel, sPendingNotes, sPendingUrl;
    private static AlertDialog sShowing;

    private CocoAppUpdater() {}

    public static boolean hasPendingUpdate() {
        return sPendingUrl != null;
    }

    /** Shows the (non-dismissable) update dialog again, e.g. after the player cancelled the system prompt. */
    public static void showIfPending(Activity activity) {
        if (sPendingUrl != null) offer(activity, sPendingLabel, sPendingNotes, sPendingUrl);
    }

    /** Checks once per process, quietly: no network or no update means no UI at all. */
    public static void checkOnStart(Activity activity) {
        if (sCheckedThisRun) { showIfPending(activity); return; }
        sCheckedThisRun = true;
        if (activity.getPackageName().endsWith(".debug")) return; // debug builds use another key
        PojavApplication.sExecutorService.execute(() -> {
            try {
                JsonObject info = JsonParser.parseString(DownloadUtils.downloadString(VERSION_URL)).getAsJsonObject();
                long latest = info.get("versionCode").getAsLong();
                if (latest <= installedVersionCode(activity)) return;
                String versionName = info.has("versionName") ? info.get("versionName").getAsString() : "";
                String notes = info.has("notes") ? info.get("notes").getAsString() : "";
                JsonObject apks = info.getAsJsonObject("apks");
                String abi = Architecture.getDeviceArchitecture() == Architecture.ARCH_X86_64 ? "x86_64" : "arm64-v8a";
                if (apks == null || !apks.has(abi)) return;
                String apkUrl = APK_BASE_URL + apks.get(abi).getAsString();
                sPendingLabel = versionName + " (bản dựng " + latest + ")";
                sPendingNotes = notes;
                sPendingUrl = apkUrl;
                Tools.runOnUiThread(() -> showIfPending(activity));
            } catch (Exception e) {
                Log.i(TAG, "Update check skipped: " + e);
            }
        });
    }

    private static long installedVersionCode(Context ctx) throws Exception {
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static void offer(Activity activity, String versionName, String notes, String apkUrl) {
        if (activity.isFinishing() || (sShowing != null && sShowing.isShowing())) return;
        String msg = "Đã có bản CocoLauncher mới: " + versionName + ". Cần cập nhật để tiếp tục chơi — dữ liệu và gói mod vẫn được giữ nguyên."
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        sShowing = new AlertDialog.Builder(activity)
                .setTitle("Bắt buộc cập nhật")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Cập nhật", (d, w) -> download(activity, apkUrl))
                .show();
    }

    private static void download(Activity activity, String apkUrl) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Đang tải bản cập nhật…")
                .setView(bar)
                .setCancelable(false)
                .show();
        File apk = new File(activity.getCacheDir(), "coco-update.apk");
        PojavApplication.sExecutorService.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int p = (int) (done * 1000 / total);
                            Tools.runOnUiThread(() -> bar.setProgress(p));
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                install(activity, apk);
                Tools.runOnUiThread(progress::dismiss);
            } catch (Exception e) {
                Log.w(TAG, "Update failed", e);
                Tools.runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, "Không tải được bản cập nhật: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    showIfPending(activity);
                });
            }
        });
    }

    /** Streams the APK into a PackageInstaller session; CocoInstallReceiver shows the system prompt. */
    private static void install(Context ctx, File apk) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("coco.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            // Registered at runtime: Mojo wraps the app context (LocaleUtils), so Android can't
            // instantiate a manifest-declared receiver ("cannot be cast to ContextImpl").
            Context app = ctx.getApplicationContext();
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(new CocoInstallReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(new CocoInstallReceiver(), filter);
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, status, flags);
            session.commit(pi.getIntentSender());
        }
    }
}
