package net.kdt.pojavlaunch.coco;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

import git.artdeell.mojo.R;

/** RAM, renderer, resolution, touch layout, logs. Values are Mojo's own preferences. */
public class CocoSettingsDialog {
    private static final int RAM_STEP = 256;
    private static final int RAM_MIN = 1024;
    private static final int RES_MIN = 25;

    private final CocoHomeFragment mHome;

    public CocoSettingsDialog(CocoHomeFragment home) {
        mHome = home;
    }

    /** ~60% of device RAM, 2.5–4 GB. 2 GB runs out of heap while the Lite pack loads its resources. */
    static int recommendedRam(int deviceRamMb) {
        int r = Math.max(2560, Math.min(4096, deviceRamMb * 6 / 10));
        return r / RAM_STEP * RAM_STEP;
    }

    private static int maxRam(int deviceRamMb) {
        int m = Math.max(2560, deviceRamMb - 768);
        return m / RAM_STEP * RAM_STEP;
    }

    public void show() {
        Context ctx = mHome.requireContext();
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_coco_settings, null);
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 48), dp(ctx, 820));
            int height = ctx.getResources().getDisplayMetrics().heightPixels - dp(ctx, 32);
            w.setLayout(width, height);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }

        bindRam(ctx, root);
        bindResolution(root);
        bindRenderer(root);

        root.findViewById(R.id.coco_settings_close).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.coco_edit_controls).setOnClickListener(v -> { dialog.dismiss(); mHome.openControlsEditor(); });
        root.findViewById(R.id.coco_send_log).setOnClickListener(v -> Tools.shareLog(ctx));
        root.findViewById(R.id.coco_open_sidemods).setOnClickListener(v -> openSideMods(ctx));
        root.findViewById(R.id.coco_verify).setOnClickListener(v -> forgetHashes(ctx));
        ((TextView) root.findViewById(R.id.coco_about)).setText(ctx.getString(R.string.coco_about, CocoHomeFragment.appVersion(ctx)));

        dialog.setOnDismissListener(d -> mHome.refreshMeta());
        dialog.show();
    }

    private void bindRam(Context ctx, View root) {
        int device = Tools.getTotalDeviceMemory(ctx);
        int max = maxRam(device);
        SeekBar seek = root.findViewById(R.id.coco_ram_seek);
        TextView value = root.findViewById(R.id.coco_ram_value);
        ((TextView) root.findViewById(R.id.coco_ram_note)).setText(ctx.getString(R.string.coco_ram_note,
                Formatter.formatShortFileSize(ctx, (long) device * 1024 * 1024), recommendedRam(device)));
        seek.setMax((max - RAM_MIN) / RAM_STEP);
        int current = Math.max(RAM_MIN, Math.min(max, LauncherPreferences.PREF_RAM_ALLOCATION));
        seek.setProgress((current - RAM_MIN) / RAM_STEP);
        value.setText(current + " MB");
        seek.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int mb = RAM_MIN + p * RAM_STEP;
            value.setText(mb + " MB");
            LauncherPreferences.PREF_RAM_ALLOCATION = mb;
            LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", mb).apply();
        }));
    }

    private void bindResolution(View root) {
        SeekBar seek = root.findViewById(R.id.coco_res_seek);
        TextView value = root.findViewById(R.id.coco_res_value);
        seek.setMax(100 - RES_MIN);
        int current = Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100);
        seek.setProgress(Math.max(0, current - RES_MIN));
        value.setText(current + " %");
        seek.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int pct = RES_MIN + p;
            value.setText(pct + " %");
            LauncherPreferences.PREF_SCALE_FACTOR = pct / 100f;
            LauncherPreferences.DEFAULT_PREF.edit().putInt("resolutionRatio", pct).apply();
        }));
    }

    private void bindRenderer(View root) {
        RadioGroup group = root.findViewById(R.id.coco_renderer_group);
        String mode = LauncherPreferences.DEFAULT_PREF.getString(CocoSetup.PREF_RENDERER_MODE, CocoSetup.RENDERER_AUTO);
        group.check(CocoSetup.RENDERER_LTW.equals(mode) ? R.id.coco_renderer_ltw
                : CocoSetup.RENDERER_ZINK.equals(mode) ? R.id.coco_renderer_zink : R.id.coco_renderer_auto);
        group.setOnCheckedChangeListener((g, id) -> {
            String m = id == R.id.coco_renderer_ltw ? CocoSetup.RENDERER_LTW
                    : id == R.id.coco_renderer_zink ? CocoSetup.RENDERER_ZINK : CocoSetup.RENDERER_AUTO;
            LauncherPreferences.DEFAULT_PREF.edit().putString(CocoSetup.PREF_RENDERER_MODE, m).apply();
        });
    }

    /** Opens instance/sidemods in the system file manager (through Mojo's documents provider). */
    private static void openSideMods(Context ctx) {
        try {
            File dir = CocoSetup.sideModsDir(CocoSetup.prepareInstance());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            Tools.openPath(ctx, dir, false);
        } catch (Exception e) {
            Tools.showError(ctx, e);
        }
    }

    /** Drops the sha1 cache so the next PLAY re-hashes (and repairs) every pack file. */
    private static void forgetHashes(Context ctx) {
        Instance selected = Instances.loadSelectedInstance();
        if (selected != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(selected.getGameDirectory(), ".coco-hash-cache.json").delete();
        }
        Toast.makeText(ctx, R.string.coco_verify_done, Toast.LENGTH_LONG).show();
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    private interface IntCallback { void on(int value); }

    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final IntCallback mCallback;
        SimpleSeek(IntCallback callback) { mCallback = callback; }
        @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser) mCallback.on(progress); }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
