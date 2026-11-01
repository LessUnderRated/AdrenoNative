package app.gamenative.profile

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars

/**
 * Per-game Turnip A8xx workarounds, stored as extra TU_DEBUG flags in the game's container env.
 * The only per-game knobs the app exposes; everything else comes from [S26Profile].
 */
enum class S26Quirk(val flag: String) {
    /** Rendering glitches: bypass GMEM tiling. */
    SYSMEM(S26Profile.TU_DEBUG_SYSMEM),

    /** Game refuses to start: report the GPU as Steam Deck's. */
    DECK_EMU(S26Profile.TU_DEBUG_DECK_EMU),
    ;

    companion object {
        private const val KEY = "TU_DEBUG"

        private fun flags(container: Container): MutableList<String> =
            EnvVars(container.envVars).get(KEY).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        fun isEnabled(container: Container, quirk: S26Quirk): Boolean = quirk.flag in flags(container)

        fun set(container: Container, quirk: S26Quirk, enabled: Boolean) {
            val flags = flags(container)
            flags.remove(quirk.flag)
            if (enabled) flags.add(quirk.flag)
            val env = EnvVars(container.envVars)
            if (flags.isEmpty()) env.remove(KEY) else env.put(KEY, flags.joinToString(","))
            container.envVars = env.toString()
            container.saveData()
        }
    }
}
