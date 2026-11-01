# Re-hosting runtime downloads

The component manifest (`manifest.json`) is served from this repo, but the runtime files it and
the app point at still come from GameNative's CDN, `downloads.gamenative.app` (with an R2
fallback). Those are plain file downloads, not the API servers that `BlockedHostsInterceptor`
refuses, but they are GameNative's bandwidth and can change or disappear under us. The plan is to
mirror only what the pinned S26 stack uses onto this repo's GitHub Releases.

## Where the URLs live

| Place | What it fetches |
| --- | --- |
| `service/SteamService.kt` `fetchFileWithFallback(fileName)` | Every runtime file below. Builds `https://downloads.gamenative.app/$fileName`, then the R2 fallback. |
| `assets/*_download.json` | Catalogues for `utils/downloader/*Downloader.kt` (extras, container patterns, dxwrapper, graphics drivers, wincomponents). |
| `manifest.json` | Turnip Gen8 drivers, DXVK, VKD3D-Proton, FEX, ARM64EC Proton. |
| `ui/screen/settings/WineProtonManagerDialog.kt` | `component-manifest.json` for the Wine/Proton picker. |
| `display/GamescopePayload.kt` | `gamescope-gamenative-<ver>.tzst`. |

Out of scope (being removed with their stores, or GameNative-only): `GogMapRepository`,
`EaHelperArchive`, `RockstarHelperArchive`, `CuratedListRemoteSource`, `UpdateInstaller`.

## Files the S26 stack needs

- Image: `imagefs_bionic.txz`, `imagefs_gamenative.txz`, `imagefs_patches_gamenative.tzst`, `steam.tzst`
- Container files: `container_files/extras.tzst`, the `container_pattern_*` and
  `proton-*-arm64ec_container_pattern` entries in `assets/container_files_download.json`
- Drivers: `drivers/Turnip_Gen8_V37.zip`, `V34`, `V30`; `graphics_driver/wrapper*.tzst`,
  `graphics_driver/extra_libs.tzst`, `graphics_driver/libvulkan_wrapper.tzst`
- Wrappers: `dxwrapper/dxvk-2.7.1.tzst`, the DXVK-GPLASYNC 2.7.1 `.wcp`,
  `drivers/vkd3d-proton-3.0.1.wcp`
- Emulation and Proton: `FEXCore-2610-gn-5.wcp`, `FEXCore-2609.wcp`,
  `proton-11.0-2-arm64ec-9.wcp`, `proton-10.0-4-arm64ec.wcp`
- Windows components: the `wincomponents/*.tzst` set (direct3d, vcrun*, xaudio, ...)

Check every archive's licence before mirroring it: Wine, Proton, DXVK, VKD3D-Proton, FEX and Mesa
are free software with source available upstream. Anything without a clear licence (some
`wrapper*` builds, vendor `qcom-*` drivers) stays unmirrored.

## Steps

1. Create one release per payload group, for example `runtime-2026.11`. Release assets are flat,
   so a path such as `drivers/Turnip_Gen8_V37.zip` is stored as `drivers__Turnip_Gen8_V37.zip`.
2. Add a `tools/mirror-runtime.sh` script that downloads each file above, records its SHA-256 in
   `runtime-sha256.txt`, and uploads it to the release.
3. Add a `RUNTIME_BASE_URL` to `Constants.kt` and use it in `fetchFileWithFallback` as the primary
   URL, keeping `downloads.gamenative.app` as the fallback until the mirror is complete. Rewrite the
   URLs in `manifest.json` and `assets/*_download.json` the same way.
4. Check each download against `runtime-sha256.txt` so a changed upstream file cannot slip in.
5. Once every pinned file is mirrored, drop the GameNative CDN and R2 fallbacks.
