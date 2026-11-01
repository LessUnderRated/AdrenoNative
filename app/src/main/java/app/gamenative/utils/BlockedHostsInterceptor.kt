package app.gamenative.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * GameNative's API terms limit api.gamenative.app and relay.gamenative.app to official GameNative
 * builds (see PlayIntegrity.kt). AdrenoNative is not one, so every request to those hosts is
 * refused locally before it leaves the device. Callers already treat IOException as "offline".
 */
class BlockedHostsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (isBlocked(host)) throw BlockedHostException(host)
        return chain.proceed(chain.request())
    }

    class BlockedHostException(host: String) : IOException("Requests to $host are disabled in this build")

    companion object {
        val BLOCKED_HOSTS = setOf("api.gamenative.app", "relay.gamenative.app")

        fun isBlocked(host: String): Boolean = host.lowercase().trimEnd('.') in BLOCKED_HOSTS
    }
}
