# AdrenoNative

A lean Android app that downloads and runs your Steam games on **one device**: the Galaxy S26
(Snapdragon 8 Elite Gen 5 / Adreno 840, Android 16, arm64). It ships one pinned, pre-tuned
compatibility stack instead of config menus.

AdrenoNative is a fork of [GameNative](https://github.com/utkarshdalal/GameNative) (imported at
upstream `85715072`), which builds on Winlator and Pluvia. It is not affiliated with GameNative and
does not use GameNative's servers.

> **Status: early.** The app builds, but nothing has been tested on an S26 yet. Expect breakage.

## The stack

All of it is pinned in [`S26Profile.kt`](app/src/main/java/app/gamenative/profile/S26Profile.kt):

| Layer | Pinned |
| --- | --- |
| GPU driver | Turnip A8xx, `Turnip Gen8 V37` (whitebelyash gen8 builds) |
| Direct3D 8–11 | DXVK 2.7.1 |
| Direct3D 12 | VKD3D-Proton 2.14.1 |
| Wine | Proton 11.0-2 ARM64EC |
| x86 emulation | FEX 2610 (`INTERMEDIATE` preset) |
| Display | 120 Hz |
| Power | Samsung CPU 1–3 / GPU 2–4 levels (needs the optional Samsung SDK) |

Known A8xx Turnip quirks: add `TU_DEBUG=sysmem` to a game's env vars for rendering glitches, or
`TU_DEBUG=deck_emu` if it won't start.

The downloadable components are listed in [`manifest.json`](manifest.json), which the app fetches
from this repo. It's trimmed to the stack above.

## How it differs from GameNative

- **One device, one stack.** No legacy, XR or armeabi-v7a builds. Other GPUs fall back to
  GameNative's generic defaults and aren't supported.
- **Open exec shim.** Android 10+ blocks `execve()` of files in app data. GameNative works around
  this with a closed-source `libredirect`. AdrenoNative uses
  [`libadrenoexec.so`](app/src/main/cpp/adrenoexec), an LD_PRELOAD shim in the style of
  termux-exec that runs those binaries through `/system/bin/linker64`. It's built from source on
  every build.
- **No GameNative services.** Requests to `api.gamenative.app` and `relay.gamenative.app` are
  refused in the app (`BlockedHostsInterceptor`). GameNative's API terms limit those servers to
  official builds, and we don't spoof their integrity checks. The remote "best config" is
  replaced by the local S26 profile.
- **No proprietary blobs.** GameNative's closed binaries and the Samsung Performance SDK are not
  in the repo.

## Building

Requirements: JDK 17+, Android SDK 36, NDK 27.3.13750724, CMake 3.22.1.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleModernDebug
```

The APK installs as `app.adrenonative`, next to GameNative if you have it.

**Optional Samsung Performance SDK:** download `perfsdk-v1.0.0.jar` from Samsung and place it at
`app/src/main/lib/perfsdk-v1.0.0.jar`. The build picks it up and enables CPU/GPU level control. It
is git-ignored; don't commit it.

**Exec shim host test:** `app/src/main/cpp/adrenoexec/test/host_test.sh` runs the shim's
rewrite logic on a Linux host, using glibc's `ld.so` in place of `linker64`.

## Roadmap

1. ~~Build~~ · ~~block GameNative servers~~ · ~~open exec shim~~ · ~~own manifest~~ · ~~S26 profile~~
2. Test on the S26 and tune the profile
3. Re-host runtime downloads on this repo's releases (they still come from
   `downloads.gamenative.app`). See [`docs/REHOSTING.md`](docs/REHOSTING.md).
4. Strip Epic, GOG, Amazon, EA and Rockstar; RevenueCat billing; PostHog analytics

## License

[GPL-3.0](LICENSE), same as GameNative. See [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).
