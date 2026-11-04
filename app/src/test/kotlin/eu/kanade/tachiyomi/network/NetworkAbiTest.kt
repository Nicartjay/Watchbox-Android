package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NetworkAbiTest {
    @Test
    fun `extension cookie jar getter has the AndroidCookieJar JVM descriptor`() {
        assertEquals(AndroidCookieJar::class.java, NetworkHelper::class.java.getMethod("getCookieJar").returnType)
        assertNotNull(AndroidCookieJar::class.java.getConstructor())
    }

    @Test
    fun `both legacy and regular HTTP getter descriptors remain compatible`() {
        listOf("getClient", "getCloudflareClient").forEach {
            assertEquals(OkHttpClient::class.java, NetworkHelper::class.java.getMethod(it).returnType)
        }
        assertEquals(String::class.java, NetworkHelper::class.java.getMethod("defaultUserAgentProvider").returnType)
    }

    @Test
    fun `extension-facing cookie helpers retain their method shapes`() {
        val type = AndroidCookieJar::class.java
        assertEquals(List::class.java, type.getMethod("get", HttpUrl::class.java).returnType)
        assertEquals(
            Int::class.javaPrimitiveType,
            type.getMethod("remove", HttpUrl::class.java, List::class.java, Int::class.javaPrimitiveType).returnType,
        )
        assertNotNull(type.getMethod("removeAll"))
    }
}
