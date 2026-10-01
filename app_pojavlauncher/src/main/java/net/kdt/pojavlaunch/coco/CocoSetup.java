package net.kdt.pojavlaunch.coco;

import android.content.Context;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.AuthType;
import net.kdt.pojavlaunch.authenticator.accounts.Account;
import net.kdt.pojavlaunch.authenticator.accounts.Accounts;
import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/** Puts the launcher in the state "Play" expects: one local account, one Coco instance, Fabric installed. */
public final class CocoSetup {
    private CocoSetup() {}

    public static final String PREF_RENDERER_MODE = "cocoRendererMode";
    public static final String RENDERER_AUTO = "auto";
    public static final String RENDERER_LTW = "ltw";
    public static final String RENDERER_ZINK = "zink";

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    public static boolean isValidUsername(String name) {
        return name != null && USERNAME.matcher(name).matches();
    }

    /** @return the name of the selected local account, or "" */
    public static String currentUsername() {
        Account current = Accounts.getCurrent();
        return current != null && current.isLocal() ? current.username : "";
    }

    /** Selects the local account with this name, creating it the first time. */
    public static void useAccount(String username) throws IOException {
        for (Account a : Accounts.load().accounts) {
            if (a.isLocal() && username.equals(a.username)) {
                Accounts.setCurrent(a);
                return;
            }
        }
        Account created = Accounts.create(acc -> {
            acc.username = username;
            acc.authType = AuthType.LOCAL;
            acc.isMicrosoft = false;
        });
        Accounts.setCurrent(created);
    }

    /** Finds (or creates) the Coco instance, applies the launcher's settings to it and selects it. */
    public static Instance prepareInstance() throws IOException {
        Instance coco = null;
        for (Instance i : Instances.loadAllInstances()) {
            if (CocoConfig.INSTANCE_NAME.equals(i.name)) { coco = i; break; }
        }
        if (coco == null) {
            coco = Instances.createInstance(i -> {
                i.name = CocoConfig.INSTANCE_NAME;
                i.sharedData = false;
            }, CocoConfig.INSTANCE_DIR_PREFIX);
        }
        coco.versionId = CocoConfig.VERSION_ID;
        coco.renderer = rendererId(LauncherPreferences.DEFAULT_PREF.getString(PREF_RENDERER_MODE, RENDERER_AUTO));
        coco.controlLayout = null; // use the default layout, which installControls() points at ours
        // SideMods: the player's own extra mods, never synced or deleted (same as the PC launcher).
        File sideMods = sideModsDir(coco);
        //noinspection ResultOfMethodCallIgnored
        sideMods.mkdirs();
        coco.jvmArgs = "-Dfabric.addMods=" + sideMods.getAbsolutePath();
        coco.argsMode = Instance.ARGS_MODE_MERGE_DEFAULT_FIRST;
        coco.write();
        Instances.setSelectedInstance(coco);
        return coco;
    }

    public static File sideModsDir(Instance instance) {
        return new File(instance.getGameDirectory(), "sidemods");
    }

    private static String rendererId(String mode) {
        if (RENDERER_ZINK.equals(mode)) return Renderers.ZINK_RENDERER;
        return Renderers.LTW_RENDERER; // auto = LTW, the fast path for 1.17+
    }

    /** Downloads the Fabric profile json the first time. Blocking. */
    public static void ensureFabric() throws IOException {
        File json = new File(Tools.DIR_HOME_VERSION, CocoConfig.VERSION_ID + "/" + CocoConfig.VERSION_ID + ".json");
        if (json.isFile()) return;
        String id = FabriclikeUtils.FABRIC_UTILS.install(CocoConfig.MINECRAFT_VERSION, CocoConfig.FABRIC_LOADER_VERSION);
        if (id == null) throw new IOException("Không cài được Fabric");
    }

    /** Copies the Coco touch layout (P/R/C/M + party keys) once and makes it the default layout. */
    public static void installControls(Context ctx) throws IOException {
        File target = new File(Tools.CTRLMAP_PATH, CocoConfig.CONTROLS_FILE);
        Tools.copyAssetFile(ctx.getAssets(), CocoConfig.CONTROLS_FILE, target, false);
        String path = target.getAbsolutePath();
        if (!path.equals(LauncherPreferences.PREF_DEFAULTCTRL_PATH)) {
            LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", path).apply();
            LauncherPreferences.PREF_DEFAULTCTRL_PATH = path;
        }
    }
}
