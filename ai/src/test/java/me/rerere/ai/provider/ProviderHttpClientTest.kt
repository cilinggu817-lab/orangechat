package me.rerere.ai.provider

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProviderHttpClientTest {
    @Test
    fun `chat transport is isolated without changing websocket client`() {
        val shared = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request()) }
            .build()
        val chat = shared.forProviderRequests()

        assertEquals(listOf(Protocol.HTTP_1_1), chat.protocols)
        assertEquals(0, chat.pingIntervalMillis)
        assertEquals(10_000, shared.pingIntervalMillis)
        assertTrue(shared.protocols.contains(Protocol.HTTP_2))
        assertNotSame(shared.connectionPool, chat.connectionPool)
        assertSame(shared.dispatcher, chat.dispatcher)
        assertEquals(shared.interceptors, chat.interceptors)
        assertEquals(shared.connectTimeoutMillis, chat.connectTimeoutMillis)
        assertEquals(shared.readTimeoutMillis, chat.readTimeoutMillis)
        assertEquals(shared.writeTimeoutMillis, chat.writeTimeoutMillis)
        assertSame(shared.proxySelector, chat.proxySelector)
        assertSame(shared.authenticator, chat.authenticator)
    }

    @Test
    fun `multiple delayed generations preserve requests and streamed response`() {
        MockWebServer().use { server ->
            server.start()
            val shared = OkHttpClient.Builder()
                .pingInterval(100, TimeUnit.MILLISECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("X-Test", "kept").build())
                }.build()
            val chat = shared.forProviderRequests()
            val body = """{"model":"test","messages":[{"role":"user","content":"你好"}],"stream":true}"""
            val events = "data: {\"text\":\"hello\"}\n\ndata: [DONE]\n\n"

            repeat(3) {
                server.enqueue(MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .headersDelay(300, TimeUnit.MILLISECONDS)
                    .body(events)
                    .build())
                val request = Request.Builder().url(server.url("/v1/chat/completions"))
                    .header("Authorization", "Bearer test-only")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                chat.newCall(request).execute().use { response ->
                    assertEquals(Protocol.HTTP_1_1, response.protocol)
                    assertEquals(events, response.body.string())
                }
                val received = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", received.method)
                assertEquals("Bearer test-only", received.headers["Authorization"])
                assertEquals("kept", received.headers["X-Test"])
                assertEquals(body, received.body!!.utf8())
            }
            assertEquals(3, server.requestCount)
            chat.connectionPool.evictAll()
            chat.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `cancel while waiting for headers does not replay the generation`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("ok").build())
            val chat = OkHttpClient().forProviderRequests()
            val finished = CountDownLatch(1)
            val failure = AtomicReference<IOException>()
            val call = chat.newCall(Request.Builder().url(server.url("/v1/chat/completions"))
                .post("{}".toRequestBody("application/json".toMediaType())).build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failure.set(e)
                    finished.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    finished.countDown()
                }
            })
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNotNull(failure.get())
            assertTrue(call.isCanceled())
            assertEquals(1, server.requestCount)
            chat.connectionPool.evictAll()
            chat.dispatcher.executorService.shutdown()
        }
    }
}
