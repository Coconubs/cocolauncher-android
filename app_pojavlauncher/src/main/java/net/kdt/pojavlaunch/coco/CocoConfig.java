package net.kdt.pojavlaunch.coco;

/** Everything specific to the Coco Cobblemon server, in one place. */
public final class CocoConfig {
    private CocoConfig() {}

    /** Release "android" of the public pack repo: manifest.json + the files only the
     * Lite pack has. Files shared with the PC pack point back into its releases
     * through a relative downloadName ("../pack/…"). */
    public static final String PACK_BASE_URL = "https://github.com/Coconubs/cocolauncher-pack/releases/download/android";
    public static final String PACK_MANIFEST = "manifest.json";

    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String FABRIC_LOADER_VERSION = "0.18.4";
    public static final String VERSION_ID = "fabric-loader-" + FABRIC_LOADER_VERSION + "-" + MINECRAFT_VERSION;

    public static final String INSTANCE_NAME = "Coco Cobblemon";
    public static final String INSTANCE_DIR_PREFIX = "coco";

    public static final String SERVER_HOST = "185.207.166.112";
    public static final int SERVER_PORT = 19017;

    public static final String DISCORD_URL = "https://discord.gg/hyspace";

    /** Touch layout shipped in assets/, copied once into the control map folder. */
    public static final String CONTROLS_FILE = "coco_controls.json";

    /** Folders the launcher keeps identical to the manifest (extra files deleted). */
    public static final String[] MIRROR_FOLDERS = {"mods", "resourcepacks"};
}
