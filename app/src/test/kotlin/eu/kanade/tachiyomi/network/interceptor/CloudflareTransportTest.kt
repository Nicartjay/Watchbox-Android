package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.FakeWebCookieStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareTransportTest {
    @Test
    fun `real OkHttp bridge sends browser clearance on retry without stale or duplicate headers`() {
        val storage = FakeWebCookieStorage()
        val jar = AndroidCookieJar { storage }
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val url = "http://127.0.0.1:${server.localPort}/catalogue?lang=en"
            storage.setCookie(url, "cf_clearance=stale; Path=/")
            storage.setCookie(url, "session=account; Path=/")
            val received = pool.submit<List<List<String>>> {
                (0..1).map { attempt ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }
                        val body = if (attempt == 0) "verify" else "catalogue"
                        val status = if (attempt == 0) "403 Forbidden" else "200 OK"
                        val challenge = if (attempt == 0) "cf-mitigated: challenge\r\n" else ""
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status\r\n$challenge" +
                                    "Content-Type: text/html\r\nContent-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n$body").toByteArray(),
                            )
                            flush()
                        }
                        headers
                    }
                }
            }
            val client = OkHttpClient.Builder()
                .cookieJar(jar)
                .callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(UserAgentInterceptor { "WatchBox-browser-UA" })
                .addInterceptor(
                    CloudflareInterceptor(jar) { challenged, _ ->
                        assertEquals("WatchBox-browser-UA", challenged.header("User-Agent"))
                        storage.setCookie(challenged.url.toString(), "cf_clearance=browser-fresh; Path=/")
                        true
                    },
                )
                .build()
            try {
                client.newCall(Request.Builder().url(url).header("Referer", url).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("catalogue", it.body.string())
                }
                val headers = received.get(8, TimeUnit.SECONDS)
                val firstCookie = headers[0].first { it.startsWith("Cookie:", ignoreCase = true) }
                val retryCookie = headers[1].first { it.startsWith("Cookie:", ignoreCase = true) }
                assertTrue(firstCookie.contains("cf_clearance=stale"))
                assertTrue(retryCookie.contains("cf_clearance=browser-fresh"))
                assertTrue(retryCookie.contains("session=account"))
                assertFalse(retryCookie.contains("cf_clearance=stale"))
                headers.forEach { request ->
                    assertEquals(1, request.count { it.startsWith("User-Agent:", ignoreCase = true) })
                    assertEquals(1, request.count { it.startsWith("Cookie:", ignoreCase = true) })
                    assertTrue(request.any { it.equals("Referer: $url", ignoreCase = true) })
                    assertEquals("GET /catalogue?lang=en HTTP/1.1", request.first())
                }
            } finally {
                client.connectionPool.evictAll()
                pool.shutdownNow()
            }
        }
    }
}
