package me.rerere.ai.provider

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Transport for the relay-compatible build. Some relays reset HTTP/2 streams while
 * waiting for response headers. Use HTTP/1.1 for provider requests, including SSE,
 * without replaying a potentially billable generation after a transport failure.
 *
 * Keep the shared client's authentication, proxy, timeouts and interceptors. Its
 * WebSocket users still need their ping interval; neither their configuration nor
 * their pool should be changed by chat's transport policy.
 */
internal fun OkHttpClient.forProviderRequests(): OkHttpClient = newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .pingInterval(0, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool())
    .build()
