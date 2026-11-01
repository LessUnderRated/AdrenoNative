package app.gamenative.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedHostsInterceptorTest {
    @Test
    fun blocksGameNativeServers() {
        assertTrue(BlockedHostsInterceptor.isBlocked("api.gamenative.app"))
        assertTrue(BlockedHostsInterceptor.isBlocked("RELAY.gamenative.app."))
        assertFalse(BlockedHostsInterceptor.isBlocked("downloads.gamenative.app"))
        assertFalse(BlockedHostsInterceptor.isBlocked("raw.githubusercontent.com"))
    }

    @Test
    fun netHttpRefusesBeforeNetwork() {
        for (url in listOf("https://api.gamenative.app/api/update-check", "https://relay.gamenative.app/api/app")) {
            assertThrows(BlockedHostsInterceptor.BlockedHostException::class.java) {
                Net.http.newCall(Request.Builder().url(url).build()).execute()
            }
        }
    }

    @Test
    fun separateClientsAlsoBlocked() {
        val client = OkHttpClient.Builder().addInterceptor(BlockedHostsInterceptor()).build()
        assertThrows(BlockedHostsInterceptor.BlockedHostException::class.java) {
            client.newCall(Request.Builder().url("https://api.gamenative.app/").build()).execute()
        }
    }
}
