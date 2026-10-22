package com.github.nepyh.rooter.common.monitoring

import com.github.nepyh.rooter.module.configureErrorResponses
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.JobRunRepo
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.StringWriter
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

class ErrorMonitoringTest : StringSpec({
    val config = MonitoringConfig("https://public@example.invalid/1", "test", "test-commit")
    lateinit var transport: MemoryTransport

    beforeTest {
        Sentry.close()
        Sentry.init(ErrorMonitoring.options(config).apply {
            setTransportFactory { options, _ ->
                MemoryTransport(options).also { transport = it }
            }
        })
    }
    afterTest { ErrorMonitoring.close() }

    "빈 DSN 은 수집을 끄고 잘못된 DSN 도 애플리케이션을 종료하지 않는다" {
        ErrorMonitoring.initialize(config.copy(dsn = ""))
        Sentry.isEnabled() shouldBe false
        ErrorMonitoring.capture(IllegalStateException("private message"), ErrorSource.HTTP)
        transport.events.size shouldBe 0
        ErrorMonitoring.initialize(config.copy(dsn = "invalid-dsn"))
        Sentry.isEnabled() shouldBe false
    }

    "실제 전송 envelope 에는 민감한 scope 와 SQL 메시지가 없고 코드 위치는 남는다" {
        val secret = "private-student-123"
        Sentry.configureScope { scope ->
            scope.user = User().apply { email = "$secret@example.com"; id = secret; ipAddress = "192.0.2.42" }
            scope.request = Request().apply {
                url = "https://example.com/api/students/$secret"
                queryString = "token=$secret"
                headers = mapOf("Authorization" to "Bearer $secret", "Cookie" to secret)
                cookies = secret
                data = mapOf("password" to secret)
            }
            scope.setExtra("sql", "SELECT '$secret'")
            scope.setTag("student", secret)
            scope.setTransaction(secret)
            scope.setContexts("student", mapOf("name" to secret))
            scope.addBreadcrumb(Breadcrumb().apply { message = secret })
            scope.addAttachment(Attachment(secret.toByteArray(), "private.txt"))
            scope.addAttachment(Attachment(ByteArray(0), "$secret@example.com.txt"))
        }
        val cause = IllegalStateException("SQL SELECT '$secret'", IllegalArgumentException(secret)).apply {
            stackTrace = arrayOf(StackTraceElement("com.github.nepyh.rooter.TestService", "save", "/private/$secret/TestService.kt", 42))
        }
        ErrorMonitoring.capture(cause, ErrorSource.HTTP)
        transport.events.size shouldBe 1
        val jsonText = transport.events.single()
        jsonText.contains(secret) shouldBe false
        jsonText.contains("192.0.2.42") shouldBe false
        jsonText.contains("/private/") shouldBe false
        transport.headers.joinToString().contains(secret) shouldBe false
        transport.itemTypes.all { it == SentryItemType.Event } shouldBe true
        val event = Json.parseToJsonElement(jsonText).jsonObject
        setOf("request", "user", "message", "extra", "breadcrumbs", "server_name", "threads", "modules").forEach {
            event.containsKey(it) shouldBe false
        }
        event["environment"]!!.jsonPrimitive.content shouldBe "test"
        event["release"]!!.jsonPrimitive.content shouldBe "test-commit"
        event["tags"]!!.jsonObject.keys shouldBe setOf("error.source")
        val exceptions = event["exception"]!!.jsonObject["values"]!!.jsonArray
        exceptions.size shouldBe 2
        val frame = exceptions.last().jsonObject["stacktrace"]!!.jsonObject["frames"]!!.jsonArray.single().jsonObject
        frame["filename"]!!.jsonPrimitive.content shouldBe "TestService.kt"
        frame["function"]!!.jsonPrimitive.content shouldBe "save"
        frame["lineno"]!!.jsonPrimitive.content shouldBe "42"
    }

    "취소와 4xx 및 명시한 경계를 거치지 않은 SDK 이벤트는 수집하지 않는다" {
        ErrorMonitoring.capture(CancellationException("shutdown"), ErrorSource.SCHEDULER)
        ErrorMonitoring.capture(IllegalStateException("wrapped", CancellationException("shutdown")), ErrorSource.STARTUP)
        listOf(400, 401, 403, 404, 409, 422, 429).forEach {
            ErrorMonitoring.capture(IllegalStateException("expected"), ErrorSource.HTTP, it)
        }
        Sentry.captureException(IllegalArgumentException("must not escape"))
        Sentry.captureMessage("must not escape")
        transport.events.size shouldBe 0
    }

    "HTTP 500 과 도메인 502 는 한 번 수집하고 기존 오류 응답과 4xx 제외를 유지한다" {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json() }
            configureErrorResponses()
            routing {
                get("/server-error") { throw IllegalStateException("private database error") }
                get("/upstream-error") { throw NiceApiException.ServerException() }
                get("/bad-request") { throw BadRequestException("invalid") }
                get("/unauthorized") { throw NiceApiException.InvalidKeyException() }
                get("/rate-limited") { throw NiceApiException.RateLimitedException() }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                val unhandled = get("/server-error")
                unhandled.statusCode() shouldBe 500
                Json.parseToJsonElement(unhandled.body()).jsonObject["code"]!!.jsonPrimitive.content shouldBe "INTERNAL_SERVER_ERROR"
                transport.events.size shouldBe 1
                val upstream = get("/upstream-error")
                upstream.statusCode() shouldBe 502
                Json.parseToJsonElement(upstream.body()).jsonObject["code"]!!.jsonPrimitive.content shouldBe "NICE_SERVER_ERROR"
                transport.events.size shouldBe 2
                get("/bad-request").statusCode() shouldBe 400
                get("/unauthorized").statusCode() shouldBe 401
                get("/rate-limited").statusCode() shouldBe 429
                get("/missing").statusCode() shouldBe 404
                transport.events.size shouldBe 2
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    "스케줄러 평가 실패는 수집하고 정상 종료 취소는 다시 던진다" {
        fun engine(failure: Exception) = SchedulerEngine(listOf(object : SchedulerJob {
            override val jobType = "test"
            override fun findDue(now: OffsetDateTime): List<DueJob> = throw failure
            override suspend fun execute(due: DueJob) = error("실행하면 안 된다")
        }), JobRunRepo())
        engine(IllegalStateException("private job payload")).runTick()
        transport.events.size shouldBe 1
        Json.parseToJsonElement(transport.events.single()).jsonObject["tags"]!!.jsonObject["error.source"]!!.jsonPrimitive.content shouldBe "scheduler"
        shouldThrow<CancellationException> { engine(CancellationException("shutdown")).runTick() }
        transport.events.size shouldBe 1
    }
})

/** SDK 가 직렬화한 이벤트만 메모리에 저장한다. 네트워크 전송은 하지 않는다. */
private class MemoryTransport(private val options: SentryOptions) : ITransport {
    val events = CopyOnWriteArrayList<String>()
    val headers = CopyOnWriteArrayList<String>()
    val itemTypes = CopyOnWriteArrayList<SentryItemType>()
    private val limiter = RateLimiter(options)
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        headers += StringWriter().also { options.serializer.serialize(envelope.header, it) }.toString()
        envelope.items.forEach { item ->
            // 크기 제한을 초과한 첨부 파일은 SDK 가 직렬화 시점에 제외한다.
            runCatching { item.data }.getOrNull()?.let { data ->
                itemTypes += item.header.type
                if (item.header.type == SentryItemType.Event) events += data.toString(Charsets.UTF_8)
            }
        }
    }
    override fun flush(timeoutMillis: Long) = Unit
    override fun getRateLimiter(): RateLimiter = limiter
    override fun close() = limiter.close()
    override fun close(isRestarting: Boolean) = close()
}
