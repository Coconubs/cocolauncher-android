package net.kdt.pojavlaunch.coco;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

import java.io.File;

import git.artdeell.mojo.R;

/** The only screen players see: a name, a PLAY button, and progress while the pack syncs. */
public class CocoHomeFragment extends Fragment {
    public static final String TAG = "CocoHomeFragment";
    private static final String PREF_RAM_INITIALIZED = "cocoRamInitialized";

    private EditText mName;
    private Button mPlay;
    private View mReady, mBusy;
    private TextView mTitle, mSubtitle, mBusyTitle, mBusyPercent, mBusyDetail, mMeta, mStatusText;
    private ImageView mStatusDot;
    private ProgressBar mBusyBar;

    private Context mAppCtx;
    private boolean mWorking;
    private volatile boolean mLaunchRequested;
    private long mSpeedMark, mSpeedBytes;
    private String mSpeedText = "";

    /** Minecraft/runtime downloads started by LAUNCH_GAME report through ProgressKeeper. */
    private final ProgressListener mGameProgress = new ProgressListener() {
        @Override public void onProgressStarted() {}
        @Override public void onProgressEnded() {}
        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                String text = resid > 0 ? getString(resid, va) : getString(R.string.coco_step_game, CocoConfig.MINECRAFT_VERSION);
                showProgress(text, progress * 10, "");
            });
        }
    };

    /** When every launcher task has finished the game is starting (or failed): back to ready. */
    private final TaskCountListener mTaskCount = count -> {
        if (count == 0 && mWorking && mLaunchRequested) {
            Tools.runOnUiThread(() -> { mLaunchRequested = false; setWorking(false); });
        }
        return false;
    };

    public CocoHomeFragment() {
        super(R.layout.fragment_coco_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mName = view.findViewById(R.id.coco_name);
        mPlay = view.findViewById(R.id.coco_play);
        mReady = view.findViewById(R.id.coco_ready);
        mBusy = view.findViewById(R.id.coco_busy);
        mTitle = view.findViewById(R.id.coco_title);
        mSubtitle = view.findViewById(R.id.coco_subtitle);
        mBusyTitle = view.findViewById(R.id.coco_busy_title);
        mBusyPercent = view.findViewById(R.id.coco_busy_percent);
        mBusyDetail = view.findViewById(R.id.coco_busy_detail);
        mBusyBar = view.findViewById(R.id.coco_busy_bar);
        mMeta = view.findViewById(R.id.coco_meta);
        mStatusText = view.findViewById(R.id.coco_status_text);
        mStatusDot = view.findViewById(R.id.coco_status_dot);

        mAppCtx = view.getContext().getApplicationContext();
        initDefaultRam(view.getContext());
        mName.setText(CocoSetup.currentUsername());
        mPlay.setOnClickListener(v -> onPlay());
        mName.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) { onPlay(); return true; }
            return false;
        });
        view.findViewById(R.id.coco_discord).setOnClickListener(v -> Tools.openURL(requireActivity(), CocoConfig.DISCORD_URL));
        view.findViewById(R.id.coco_controls).setOnClickListener(v -> openControlsEditor());
        view.findViewById(R.id.coco_settings).setOnClickListener(v -> new CocoSettingsDialog(this).show());

        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_GAME, mGameProgress);
        ProgressKeeper.addListener(ProgressLayout.UNPACK_RUNTIME, mGameProgress);
        ProgressKeeper.addTaskCountListener(mTaskCount, false);
        setWorking(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_GAME, mGameProgress);
        ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mGameProgress);
        ProgressKeeper.removeTaskCountListener(mTaskCount);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshMeta();
        refreshServerStatus();
    }

    void openControlsEditor() {
        try {
            CocoSetup.installControls(requireContext());
        } catch (Exception e) {
            Tools.showError(requireContext(), e);
            return;
        }
        startActivity(new Intent(requireContext(), CustomControlsActivity.class));
    }

    void refreshMeta() {
        if (mMeta == null || mWorking) return;
        mMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.coco_dim));
        mMeta.setText(getString(R.string.coco_meta, appVersion(requireContext()), LauncherPreferences.PREF_RAM_ALLOCATION));
    }

    static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** First run: Mojo's default heap is too small for ~120 mods, start from our recommendation. */
    private static void initDefaultRam(Context ctx) {
        if (LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_RAM_INITIALIZED, false)) return;
        int ram = CocoSettingsDialog.recommendedRam(Tools.getTotalDeviceMemory(ctx));
        LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", ram).putBoolean(PREF_RAM_INITIALIZED, true).apply();
        LauncherPreferences.PREF_RAM_ALLOCATION = ram;
    }

    private void refreshServerStatus() {
        PojavApplication.sExecutorService.execute(() -> {
            ServerPing.Result r = ServerPing.ping(CocoConfig.SERVER_HOST, CocoConfig.SERVER_PORT, 5000);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                int color = ContextCompat.getColor(requireContext(), r != null ? R.color.coco_green : R.color.coco_red);
                mStatusDot.setColorFilter(color);
                mStatusText.setText(r != null ? getString(R.string.coco_status_online, r.online) : getString(R.string.coco_status_offline));
            });
        });
    }

    private void onPlay() {
        if (mWorking) return;
        String name = mName.getText().toString().trim();
        if (!CocoSetup.isValidUsername(name)) {
            mName.setError(getString(R.string.coco_name_invalid));
            mName.requestFocus();
            return;
        }
        hideKeyboard();
        setWorking(true);
        showProgress(getString(R.string.coco_step_check), 0, "");
        Context appCtx = requireContext().getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                CocoSetup.useAccount(name);
                CocoSetup.installControls(appCtx);
                Instance instance = CocoSetup.prepareInstance();
                File gameDir = instance.getGameDirectory();
                mSpeedMark = System.currentTimeMillis();
                mSpeedBytes = 0;
                new CocoPackSync(gameDir, this::onSyncProgress).run();
                Tools.runOnUiThread(() -> showProgress(getString(R.string.coco_step_fabric), 1000, ""));
                CocoSetup.ensureFabric();
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    showProgress(getString(R.string.coco_step_game, CocoConfig.MINECRAFT_VERSION), 0, "");
                    mLaunchRequested = true;
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                    // Nothing to download: no task ever starts, so return to ready ourselves.
                    Tools.MAIN_HANDLER.postDelayed(() -> {
                        if (mLaunchRequested && !ProgressKeeper.hasOngoingTasks()) { mLaunchRequested = false; setWorking(false); }
                    }, 3000);
                });
            } catch (Throwable t) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setWorking(false);
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    mMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.coco_red));
                    mMeta.setText(getString(R.string.coco_sync_failed, msg));
                });
            }
        });
    }

    private void onSyncProgress(long done, long total, int filesDone, int filesTotal) {
        long now = System.currentTimeMillis();
        if (now - mSpeedMark >= 1000) {
            long speed = (done - mSpeedBytes) * 1000 / Math.max(1, now - mSpeedMark);
            mSpeedMark = now;
            mSpeedBytes = done;
            mSpeedText = Formatter.formatShortFileSize(mAppCtx, Math.max(0, speed));
        }
        int permille = total > 0 ? (int) (done * 1000 / total) : 1000;
        Tools.runOnUiThread(() -> {
            if (!isAdded()) return;
            Context c = requireContext();
            String detail = getString(R.string.coco_bytes, Formatter.formatShortFileSize(c, done), Formatter.formatShortFileSize(c, total), mSpeedText);
            showProgress(getString(R.string.coco_step_pack, filesDone, filesTotal), permille, detail);
        });
    }

    private void showProgress(String title, int permille, String detail) {
        mBusyTitle.setText(title);
        mBusyBar.setProgress(Math.max(0, Math.min(1000, permille)));
        mBusyPercent.setText((Math.max(0, Math.min(1000, permille)) / 10) + "%");
        mBusyDetail.setText(detail);
        mBusyDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setWorking(boolean working) {
        mWorking = working;
        mReady.setVisibility(working ? View.GONE : View.VISIBLE);
        mBusy.setVisibility(working ? View.VISIBLE : View.GONE);
        mTitle.setText(working ? R.string.coco_prepare_title : R.string.coco_title);
        mSubtitle.setText(working ? R.string.coco_prepare_sub : R.string.coco_subtitle);
        mMeta.setVisibility(working ? View.GONE : View.VISIBLE);
        if (!working) refreshMeta();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mName.getWindowToken(), 0);
        mName.clearFocus();
    }
}
