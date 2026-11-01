package app.gamenative.profile

import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import com.winlator.core.KeyValueSet
import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class S26ProfileTest {
    private fun repoFile(path: String): File =
        listOf(File(path), File("..", path)).first { it.exists() }

    private fun manifestIds(type: String): List<String> {
        val items = JSONObject(repoFile("manifest.json").readText()).getJSONObject("items").getJSONArray(type)
        return List(items.length()) { items.getJSONObject(it).getString("id") }
    }

    @Test
    fun pinnedComponentsAreInManifest() {
        assertTrue(S26Profile.DRIVER in manifestIds("driver"))
        assertTrue(S26Profile.PROTON in manifestIds("proton"))
        assertTrue(S26Profile.FEXCORE_VERSION in manifestIds("fexcore"))
    }

    @Test
    fun pinnedWrappersAreBundled() {
        val arrays = repoFile("app/src/main/res/values/arrays.xml").readText()
        assertTrue(arrays.contains("<item>${S26Profile.DXVK}</item>"))
        assertTrue(arrays.contains("<item>${S26Profile.VKD3D}</item>"))
    }

    @Test
    fun configsAgreeWithPins() {
        val dx = KeyValueSet(S26Profile.DXWRAPPER_CONFIG)
        assertEquals(S26Profile.DXVK, dx.get("version"))
        assertEquals(S26Profile.VKD3D, dx.get("vkd3dVersion"))
        assertEquals(S26Profile.DRIVER, KeyValueSet(S26Profile.GRAPHICS_DRIVER_CONFIG).get("version"))
        val config = S26Profile.containerConfig()
        assertEquals(S26Profile.DRIVER, config.getValue("graphicsDriverVersion").jsonPrimitive.content)
        assertEquals(S26Profile.ENV_VARS, config.getValue("envVars").jsonPrimitive.content)
    }

    @Test
    fun powerLevelsOnlyApplyToSamsungDriver() {
        val base = PowerProfile(
            enablePowerControl = true,
            adaptiveFpsCapEnabled = false,
            name = "Balanced",
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 0,
            maxCpuFreq = 4,
        )
        assertSame(base, S26Profile.tunePowerProfile(base, isSamsungDriver = false))
        val tuned = S26Profile.tunePowerProfile(base, isSamsungDriver = true)
        assertEquals(S26Profile.CPU_LEVEL_MAX.toLong(), tuned.maxCpuFreq)
        assertEquals(S26Profile.GPU_LEVEL_MAX, tuned.maxGpuPowerLevel)
        assertTrue(tuned.adaptiveFpsCapEnabled)
    }
}
