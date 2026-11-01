package app.gamenative.profile

import android.app.Activity
import android.content.Context
import android.os.Build
import app.gamenative.powercontrol.PowerProfile
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.fexcore.FEXCorePreset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * The single pinned, pre-tuned stack for the Galaxy S26 (Snapdragon 8 Elite Gen 5 / Adreno 840).
 * Every value lives here, with no config menus: container defaults (ContainerUtils,
 * Container), the per-game "best config" (BestConfigService), power levels (PowerManager) and the
 * display mode (MainActivity) all read it.
 *
 * Component ids must exist in manifest.json or in the bundled res/values/arrays.xml lists.
 */
object S26Profile {
    // Turnip A8xx build from whitebelyash's gen8 branch (manifest.json "driver").
    const val DRIVER = "Turnip Gen8 V37"
    const val GRAPHICS_DRIVER = "Wrapper"
    const val PROTON = "proton-11.0-2-arm64ec-9"
    const val EMULATOR = "FEXCore"
    const val FEXCORE_VERSION = "2610-gn-5"
    const val FEXCORE_PRESET = FEXCorePreset.INTERMEDIATE
    const val DXVK = "2.7.1"
    const val VKD3D = "2.14.1"
    const val VKD3D_FEATURE_LEVEL = "12_1"

    /** Display refresh rate games run at. 120 Hz panel; leave headroom for thermals. */
    const val REFRESH_RATE_HZ = 120f

    const val ENV_VARS =
        "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact " +
            "MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=1024MB mesa_glthread=true " +
            "WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform " +
            "VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144"

    /**
     * Known A8xx Turnip quirks, appended to TU_DEBUG for a game that needs them:
     * sysmem fixes rendering glitches (bypasses GMEM tiling, costs bandwidth);
     * deck_emu makes the driver report as Steam Deck's, for games that refuse to start.
     */
    const val TU_DEBUG_SYSMEM = "sysmem"
    const val TU_DEBUG_DECK_EMU = "deck_emu"

    const val DXWRAPPER_CONFIG =
        "version=$DXVK,framerate=0,maxDeviceMemory=0,async=0,asyncCache=0," +
            "vkd3dVersion=$VKD3D,vkd3dLevel=$VKD3D_FEATURE_LEVEL,ddrawrapper=none,csmt=3," +
            "gpuName=NVIDIA GeForce GTX 480,videoMemorySize=2048,strict_shader_math=1," +
            "OffscreenRenderingMode=fbo,renderer=gl"

    const val GRAPHICS_DRIVER_CONFIG =
        "vulkanVersion=1.3,version=$DRIVER,blacklistedExtensions=,maxDeviceMemory=0," +
            "presentMode=mailbox,syncFrame=0,disablePresentWait=0,resourceType=auto," +
            "bcnEmulation=auto,bcnEmulationType=compute,bcnEmulationCache=0,gpuName=Device"

    // Samsung Performance SDK levels (0-4). Starting values for sustained play: keep the CPU off
    // its top level (FEX is rarely the bottleneck on Oryon cores) and let the GPU run high.
    const val CPU_LEVEL_MIN = 1
    const val CPU_LEVEL_MAX = 3
    const val GPU_LEVEL_MIN = 2
    const val GPU_LEVEL_MAX = 4
    const val BUS_LEVEL_MIN = 2
    const val BUS_LEVEL_MAX = 4

    fun matches(context: Context): Boolean = GPUInformation.isAdreno8EliteGen5(context)

    /** Container settings applied to every game, in BestConfigService's config JSON format. */
    fun containerConfig(): JsonObject = JsonObject(
        mapOf(
            "containerVariant" to JsonPrimitive(Container.BIONIC),
            "wineVersion" to JsonPrimitive(PROTON),
            "emulator" to JsonPrimitive(EMULATOR),
            "fexcoreVersion" to JsonPrimitive(FEXCORE_VERSION),
            "fexcorePreset" to JsonPrimitive(FEXCORE_PRESET),
            "graphicsDriver" to JsonPrimitive(GRAPHICS_DRIVER),
            "graphicsDriverVersion" to JsonPrimitive(DRIVER),
            "graphicsDriverConfig" to JsonPrimitive(GRAPHICS_DRIVER_CONFIG),
            "dxwrapper" to JsonPrimitive("dxvk"),
            "dxwrapperConfig" to JsonPrimitive(DXWRAPPER_CONFIG),
            "envVars" to JsonPrimitive(ENV_VARS),
        ),
    )

    /** Narrows a Samsung driver profile to the S26 levels; other drivers' profiles pass through. */
    fun tunePowerProfile(profile: PowerProfile, isSamsungDriver: Boolean): PowerProfile {
        if (!isSamsungDriver) return profile
        return profile.copy(
            adaptiveFpsCapEnabled = true,
            minCpuFreq = CPU_LEVEL_MIN.toLong(),
            maxCpuFreq = CPU_LEVEL_MAX.toLong(),
            minGpuPowerLevel = GPU_LEVEL_MIN,
            maxGpuPowerLevel = GPU_LEVEL_MAX,
            minBusLevel = BUS_LEVEL_MIN,
            maxBusLevel = BUS_LEVEL_MAX,
        )
    }

    /** Requests the display mode closest to [REFRESH_RATE_HZ] at the current resolution. */
    fun applyDisplayMode(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val display = activity.display ?: return
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate <= REFRESH_RATE_HZ + 0.5f }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId == mode.modeId) return
        attrs.preferredDisplayModeId = mode.modeId
        activity.window.attributes = attrs
        Timber.tag("S26Profile").i("Display mode ${mode.modeId}: ${mode.refreshRate} Hz")
    }
}
