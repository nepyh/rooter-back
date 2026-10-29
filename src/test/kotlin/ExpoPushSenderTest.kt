import com.github.nepyh.rooter.module.notification.push.ExpoPushRequestException
import com.github.nepyh.rooter.module.notification.push.ExpoPushSender
import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.notification.push.PushResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExpoPushSenderTest : StringSpec({

    val okTicket = """{"status":"ok","id":"ticket-id"}"""
    val deviceNotRegistered =
        """{"status":"error","message":"not a registered push token","details":{"error":"DeviceNotRegistered","expoPushToken":"x"}}"""

    fun senderWith(
        accessToken: String = "",
        reportFailure: (Throwable) -> Unit = {},
        handler: MockRequestHandler
    ) = ExpoPushSender(accessToken = accessToken, httpClient = HttpClient(MockEngine(handler)), reportFailure = reportFailure)

    suspend fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    suspend fun HttpRequestData.messages(): JsonArray = Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonArray

    fun tickets(vararg ticket: String) = """{"data":[${ticket.joinToString(",")}]}"""

    fun tokens(count: Int) = (1..count).map { "ExponentPushToken[t$it]" }

    "토큰마다 메시지 하나씩 to·title·body·data·sound 를 실어 보낸다" {
        val sender = senderWith { request ->
            request.url.toString() shouldBe ExpoPushSender.DEFAULT_SEND_URL
            val message = request.messages().single().jsonObject
            message["to"]!!.jsonPrimitive.content shouldBe "ExponentPushToken[t1]"
            message["title"]!!.jsonPrimitive.content shouldBe "곧 시작"
            message["body"]!!.jsonPrimitive.content shouldBe "수학 5분 전"
            message["data"]!!.jsonObject["planTaskId"]!!.jsonPrimitive.content shouldBe "12"
            message["sound"]!!.jsonPrimitive.content shouldBe "default"
            jsonResponse(tickets(okTicket))
        }

        val result = sender.send(tokens(1), PushMessage("곧 시작", "수학 5분 전", mapOf("planTaskId" to "12")))

        result shouldBe PushResult(sent = 1, invalidTokens = emptyList())
    }

    "100개를 넘으면 100개씩 나눠 보낸다" {
        val batchSizes = mutableListOf<Int>()
        val sender = senderWith { request ->
            val size = request.messages().size
            batchSizes += size
            jsonResponse(tickets(*Array(size) { okTicket }))
        }

        val result = sender.send(tokens(150), PushMessage("제목", "본문"))

        batchSizes shouldContainExactly listOf(100, 50)
        result.sent shouldBe 150
    }

    "액세스 토큰이 있을 때만 Authorization 헤더를 붙인다" {
        val headers = mutableListOf<String?>()
        val handler: MockRequestHandler = { request ->
            headers += request.headers[HttpHeaders.Authorization]
            jsonResponse(tickets(okTicket))
        }

        senderWith(accessToken = "secret-token", handler = handler).send(tokens(1), PushMessage("제목", "본문"))
        senderWith(accessToken = "", handler = handler).send(tokens(1), PushMessage("제목", "본문"))

        headers shouldContainExactly listOf("Bearer secret-token", null)
    }

    "DeviceNotRegistered 티켓의 토큰은 invalidTokens 로 돌려주고, 그 밖의 오류는 실패로만 센다" {
        val messageTooBig = """{"status":"error","message":"too big","details":{"error":"MessageTooBig"}}"""
        val sender = senderWith { jsonResponse(tickets(okTicket, deviceNotRegistered, messageTooBig)) }

        val result = sender.send(tokens(3), PushMessage("제목", "본문"))

        result shouldBe PushResult(sent = 1, invalidTokens = listOf("ExponentPushToken[t2]"))
    }

    "Expo 가 오류 상태를 주면 예외 없이 그 묶음을 보내지 못한 것으로 친다" {
        var calls = 0
        val sender = senderWith {
            calls++
            if (calls == 1) jsonResponse("""{"errors":[{"code":"INTERNAL_SERVER_ERROR"}]}""", HttpStatusCode.InternalServerError)
            else jsonResponse(tickets(*Array(50) { okTicket }))
        }

        val result = sender.send(tokens(150), PushMessage("제목", "본문"))

        result shouldBe PushResult(sent = 50, invalidTokens = emptyList())
    }

    "네트워크 오류·응답 파싱 실패도 예외 없이 실패로 집계한다" {
        val failing = senderWith { error("connection reset") }
        val broken = senderWith { jsonResponse("not json") }

        failing.send(tokens(2), PushMessage("제목", "본문")) shouldBe PushResult.EMPTY
        broken.send(tokens(2), PushMessage("제목", "본문")) shouldBe PushResult.EMPTY
    }

    "요청이 실패한 묶음마다 오류 수집으로 넘기고, 성공·티켓 오류는 넘기지 않는다" {
        val reported = mutableListOf<Throwable>()
        var calls = 0
        val sender = senderWith(reportFailure = { reported += it }) { request ->
            when (++calls) {
                1 -> jsonResponse("""{"errors":[]}""", HttpStatusCode.TooManyRequests)
                2 -> error("connection reset")
                else -> jsonResponse(tickets(*Array(request.messages().size) { deviceNotRegistered }))
            }
        }

        val result = sender.send(tokens(201), PushMessage("제목", "본문"))

        result shouldBe PushResult(sent = 0, invalidTokens = listOf("ExponentPushToken[t201]"))
        reported.size shouldBe 2
        (reported[0] as ExpoPushRequestException).status shouldBe HttpStatusCode.TooManyRequests
        reported[1].message shouldBe "connection reset"
    }
})
