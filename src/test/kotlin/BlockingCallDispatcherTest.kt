package com.github.nepyh.rooter.common

import com.github.nepyh.rooter.module.configureErrorResponses
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 블로킹 DB 호출이 Netty call 스레드를 막지 않는지 (#361 부하 테스트에서 찾은 병목).
 * 1 vCPU 처럼 call 스레드가 1개인 서버를 띄워, 블로킹 핸들러 여러 개가 동시에 처리되는지 본다.
 */
class BlockingCallDispatcherTest : StringSpec({

    fun server(dispatcher: Boolean) = embeddedServer(Netty, configure = {
        connector { host = "127.0.0.1"; port = 0 }
        callGroupSize = 1
        workerGroupSize = 1
    }) {
        if (dispatcher) installBlockingCallDispatcher()
        install(ContentNegotiation) { json() }
        configureErrorResponses()
        routing {
            get("/thread") { call.respondText(Thread.currentThread().name) }
            get("/blocking") {
                Thread.sleep(300) // transaction { } 안의 DB 대기 대신
                call.respondText("ok")
            }
        }
    }.start(wait = false)

    suspend fun elapsedForParallel(dispatcher: Boolean, requests: Int): Long {
        val server = server(dispatcher)
        try {
            val port = server.engine.resolvedConnectors().single().port
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                get("/blocking") // 첫 요청 지연(클래스 로딩) 제외
                val start = System.nanoTime()
                withContext(Dispatchers.IO) {
                    (1..requests).map { async { get("/blocking").statusCode() } }.awaitAll()
                }.forEach { it shouldBe 200 }
                return (System.nanoTime() - start) / 1_000_000
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    "핸들러는 IO 디스패처 스레드에서 돈다" {
        val server = server(dispatcher = true)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val name = HttpClient.newHttpClient().use { client ->
                client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/thread")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body()
            }
            name shouldStartWith "DefaultDispatcher-worker"
        } finally {
            server.stop(0, 1000)
        }
    }

    "call 스레드가 1개여도 블로킹 요청 8개가 동시에 처리된다 (없으면 한 줄로 선다)" {
        // 300ms × 8 = 순서대로면 2.4초 이상, 동시면 300ms 남짓
        val without = elapsedForParallel(dispatcher = false, requests = 8)
        val with = elapsedForParallel(dispatcher = true, requests = 8)
        (without >= 2_000) shouldBe true
        (with < 1_200) shouldBe true
    }
})
