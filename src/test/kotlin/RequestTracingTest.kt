package com.github.nepyh.rooter.common.monitoring

import com.github.nepyh.rooter.module.configureErrorResponses
import com.p6spy.engine.spy.P6DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * 성능 추적(#360): 요청 트랜잭션 이름·정제, 쿼리 span 의 바인딩 값 제외, 샘플링 0 이면 끄기.
 * 쿼리 span 테스트는 로컬 PostgreSQL 이 필요하다 (TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD).
 */
class RequestTracingTest : StringSpec({
    val config = MonitoringConfig("https://public@example.invalid/1", "test", "test-commit", tracesSampleRate = 1.0)
    lateinit var transport: TraceTransport

    val testDbUrl = (System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test")
        .substringBeforeLast("/") + "/postgres"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    fun init(rate: Double) {
        Sentry.close()
        Sentry.init(ErrorMonitoring.options(config.copy(tracesSampleRate = rate)).apply {
            setTransportFactory { options, _ -> TraceTransport(options).also { transport = it } }
        })
    }

    fun withServer(module: Application.() -> Unit, block: (get: (String) -> HttpResponse<String>) -> Unit) {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json() }
            installRequestTracing()
            configureErrorResponses()
            module()
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            HttpClient.newHttpClient().use { client ->
                block { path ->
                    client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                            .header("Authorization", "Bearer private-token-abc")
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                    )
                }
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    fun tracedDataSource(): DataSource = P6DataSource(HikariDataSource(HikariConfig().apply {
        jdbcUrl = testDbUrl
        username = testDbUser
        password = testDbPassword
        maximumPoolSize = 4
    }))

    /** 트랜잭션은 응답을 보낸 뒤 finally 에서 끝나므로 클라이언트가 응답을 받은 시점보다 늦을 수 있다. */
    fun awaitTransactions(count: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (transport.transactions.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
    }

    afterTest { ErrorMonitoring.close() }

    "트랜잭션 이름은 라우트 템플릿이고 실제 id·쿼리스트링·헤더·사용자는 싣지 않는다, 헬스체크·매칭 안 된 경로는 보내지 않는다" {
        init(1.0)
        Sentry.configureScope { it.user = User().apply { id = "private-user-777"; email = "private@example.com" } }
        withServer({
            routing {
                route("api") {
                    get("plan-tasks/{taskId}") { call.respondText("ok") }
                    get("health") { call.respondText("ok") }
                    get("boom") { throw IllegalStateException("private failure") }
                    get("bad") { throw BadRequestException("private invalid") }
                }
            }
        }) { get ->
            get("/api/plan-tasks/98765432?token=private-query").statusCode() shouldBe 200
            get("/api/health").statusCode() shouldBe 200
            get("/api/boom").statusCode() shouldBe 500
            get("/api/bad").statusCode() shouldBe 400
            get("/api/no-such-route").statusCode() shouldBe 404
        }
        awaitTransactions(3)

        val transactions = transport.transactions.map { Json.parseToJsonElement(it).jsonObject }
        transactions.map { it["transaction"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder listOf(
            "GET /api/plan-tasks/{taskId}", "GET /api/boom", "GET /api/bad"
        )
        val all = transport.transactions.joinToString()
        listOf("98765432", "private-query", "private-token", "private-user", "private@example", "private failure").forEach {
            all shouldNotContain it
        }
        transactions.forEach { tx ->
            setOf("request", "user", "tags", "extra", "breadcrumbs", "server_name").forEach { tx.containsKey(it) shouldBe false }
            tx["contexts"]!!.jsonObject.keys shouldBe setOf("trace")
            // 스레드 이름·id 같은 SDK 자동 값 없이 응답 코드만
            tx["contexts"]!!.jsonObject["trace"]!!.jsonObject["data"]!!.jsonObject.keys shouldBe setOf("http.response.status_code")
        }
        fun trace(name: String): JsonObject =
            transactions.single { it["transaction"]!!.jsonPrimitive.content == name }["contexts"]!!.jsonObject["trace"]!!.jsonObject
        trace("GET /api/plan-tasks/{taskId}")["status"]!!.jsonPrimitive.content shouldBe "ok"
        trace("GET /api/boom")["status"]!!.jsonPrimitive.content shouldBe "internal_error"
        // 예외로 끝난 4xx 는 StatusPages 가 정한 응답 코드로 닫힌다 (서버 오류로 세지 않는다)
        trace("GET /api/bad")["status"]!!.jsonPrimitive.content shouldBe "invalid_argument"
        trace("GET /api/boom")["data"]!!.jsonObject["http.response.status_code"]!!.jsonPrimitive.content shouldBe "500"
    }

    "쿼리 span 은 SQL 문만 남기고 바인딩 값·DB 접속 정보는 싣지 않는다" {
        init(1.0)
        val dataSource = tracedDataSource()
        val secret = "private-student-4242"
        withServer({
            routing {
                get("/query") {
                    withContext(Dispatchers.IO) {
                        dataSource.connection.use { conn ->
                            conn.prepareStatement("SELECT ?::text AS marker").use { st ->
                                st.setString(1, secret)
                                st.executeQuery().use { it.next() }
                            }
                        }
                    }
                    call.respondText("ok")
                }
            }
        }) { get -> get("/query").statusCode() shouldBe 200 }
        awaitTransactions(1)

        val json = transport.transactions.single()
        json shouldNotContain secret
        json shouldNotContain testDbUser
        val span = Json.parseToJsonElement(json).jsonObject["spans"]!!.jsonArray
            .map { it.jsonObject }.single { it["op"]!!.jsonPrimitive.content == "db.query" }
        span["description"]!!.jsonPrimitive.content shouldBe "SELECT ?::text AS marker"
        (span["data"]?.jsonObject?.isEmpty() ?: true) shouldBe true
    }

    "동시 요청에서도 쿼리 span 이 자기 요청의 트랜잭션에만 붙는다" {
        init(1.0)
        val dataSource = tracedDataSource()
        withServer({
            routing {
                get("/a/{n}") {
                    withContext(Dispatchers.IO) { dataSource.connection.use { it.createStatement().execute("SELECT 1 AS route_a") } }
                    call.respondText("a")
                }
                get("/b/{n}") {
                    withContext(Dispatchers.IO) { dataSource.connection.use { it.createStatement().execute("SELECT 1 AS route_b") } }
                    call.respondText("b")
                }
            }
        }) { get ->
            runBlocking(Dispatchers.IO) {
                (1..10).flatMap { listOf(async { get("/a/$it") }, async { get("/b/$it") }) }.awaitAll()
            }
        }
        awaitTransactions(20)

        val transactions = transport.transactions.map { Json.parseToJsonElement(it).jsonObject }
        transactions.size shouldBe 20
        transactions.forEach { tx ->
            val marker = if (tx["transaction"]!!.jsonPrimitive.content == "GET /a/{n}") "route_a" else "route_b"
            val queries = tx["spans"]!!.jsonArray.map { it.jsonObject }.filter { it["op"]!!.jsonPrimitive.content == "db.query" }
            queries.size shouldBe 1
            queries.single()["description"]!!.jsonPrimitive.content shouldContain marker
        }
    }

    "샘플링 비율이 0 이면 플러그인을 설치하지 않고 아무것도 보내지 않는다" {
        init(0.0)
        ErrorMonitoring.isTracingEnabled() shouldBe false
        withServer({ routing { get("/x/{id}") { call.respondText("ok") } } }) { get ->
            get("/x/1").statusCode() shouldBe 200
        }
        awaitTransactions(1)
        transport.transactions.size shouldBe 0
    }

    "SQL 에 직접 들어간 문자열·숫자 리터럴은 ? 로 바꾸고 식별자는 그대로 둔다" {
        sqlTemplate("""SELECT "t1".id FROM plan_tasks t1 WHERE t1.title = 'O''Brien' AND t1.minutes > 30 LIMIT 5""") shouldBe
            """SELECT "t1".id FROM plan_tasks t1 WHERE t1.title = ? AND t1.minutes > ? LIMIT ?"""
        sqlTemplate("SELECT * FROM daily_plans WHERE user_id = ? AND date = ?") shouldBe
            "SELECT * FROM daily_plans WHERE user_id = ? AND date = ?"
    }
})

/** SDK 가 직렬화한 트랜잭션만 메모리에 저장한다. 네트워크 전송은 하지 않는다. */
private class TraceTransport(options: SentryOptions) : ITransport {
    val transactions = CopyOnWriteArrayList<String>()
    private val limiter = RateLimiter(options)
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        envelope.items.forEach { item ->
            if (item.header.type == SentryItemType.Transaction) {
                runCatching { item.data }.getOrNull()?.let { transactions += it.toString(Charsets.UTF_8) }
            }
        }
    }
    override fun flush(timeoutMillis: Long) = Unit
    override fun getRateLimiter(): RateLimiter = limiter
    override fun close() = limiter.close()
    override fun close(isRestarting: Boolean) = close()
}
