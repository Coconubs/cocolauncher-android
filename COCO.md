# CocoLauncher Android

Android launcher for the **Coco Cobblemon** Minecraft server (Fabric 1.21.1).
Fork of [MojoLauncher](https://github.com/MojoLauncher/MojoLauncher) (itself based on PojavLauncher),
distributed under the same license: **LGPL-3.0** (see `LICENSE`).

## What Coco changes
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/coco/` — the Coco home screen (name + PLAY),
  settings dialog, pack sync (`CocoPackSync`, manifest at release `android` of
  [cocolauncher-pack](https://github.com/Coconubs/cocolauncher-pack)), account/instance/Fabric setup,
  server status ping.
- `assets/coco_controls.json` — touch layout with the Cobblemon keys (P, R, C, M, party up/down).
- Auto-join the server (`--quickPlayMultiplayer`), SideMods folder (`-Dfabric.addMods`).
- x86 emulator fixes: JRE follows the app's native ABI; `-XX:UseAVX=0` when AVX is advertised but disabled.

## Building
- JDK 17+ (21 works), Android SDK platform 36, NDK 29.0.14206865 and 28.2.13676358, CMake 3.22.1.
- `git submodule update --init` (glfw, mojoexec, sdl).
- On Windows the three symlinks in `app_pojavlauncher/src/main/jni/` (glfw, mojoexec, sdl) check out as
  plain files: replace them with directory junctions to the top-level folders.
- `app_pojavlauncher/libs/ltw-release.aar` is not committed: download the `output-aar` artifact of
  [MojoLauncher/LTW](https://github.com/MojoLauncher/LTW) (LGPL-3.0) into that folder.
- Mesa libraries in `src/main/jniLibs/*/` (`libEGL_mesa`, `libgallium_dri`, `libvulkan_freedreno`, `libdrm`,
  MIT license) are taken from the official MojoLauncher nightly APK.
- Debug: `./gradlew :app_pojavlauncher:assembleFullDebug`
- Release: `./gradlew :app_pojavlauncher:assembleFullCocoRelease` — signed with the key described by
  `~/.cocolauncher/android-release.properties` (or `COCO_SIGNING`); produces one APK for arm64-v8a
  (phones) and one for x86_64 (PC emulators).
