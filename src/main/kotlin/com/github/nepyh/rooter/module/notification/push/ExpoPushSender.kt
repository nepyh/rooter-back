package com.github.nepyh.rooter.module.notification.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
private data class ExpoPushRequest(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val sound: String = "default"
)

@Serializable
private data class ExpoPushResponse(val data: List<ExpoPushTicket> = emptyList())

@Serializable
private data class ExpoPushTicket(
    val status: String,
    val message: String? = null,
    val details: ExpoPushTicketDetails? = null
)

@Serializable
private data class ExpoPushTicketDetails(val error: String? = null)

/**
 * Expo Push API 발송기. APNs·FCM 자격증명은 EAS 에 있고, 서버는 Expo 에 요청만 보낸다.
 *
 * - 한 요청에 최대 [MAX_MESSAGES_PER_REQUEST] 건이라 나눠 보낸다
 * - 응답 티켓은 보낸 순서와 같다. `DeviceNotRegistered`(앱 삭제 등) 토큰은 [PushResult.invalidTokens] 로 돌려준다
 * - [accessToken] 은 Expo 의 "Enhanced Security for Push" 를 켰을 때만 필요하다. 비어 있으면 Authorization 헤더를 붙이지 않는다
 * - 티켓 ok 는 "Expo 가 받았다"까지다. APNs 에서 거절됐는지는 영수증(receipt)으로 확인해야 하며 아직 하지 않는다
 */
class ExpoPushSender(
    private val accessToken: String,
    private val sendUrl: String = DEFAULT_SEND_URL,
    private val httpClient: HttpClient = defaultHttpClient()
) : PushSender {

    override suspend fun send(tokens: List<String>, message: PushMessage): PushResult {
        var sent = 0
        val invalidTokens = mutableListOf<String>()

        tokens.chunked(MAX_MESSAGES_PER_REQUEST).forEach { chunk ->
            val tickets = requestTickets(chunk, message) ?: return@forEach
            tickets.zip(chunk).forEach { (ticket, token) ->
                when {
                    ticket.status == "ok" -> sent++
                    ticket.details?.error == DEVICE_NOT_REGISTERED -> invalidTokens += token
                    else -> logger.warn("Expo 푸시 티켓 오류: error={}, message={}", ticket.details?.error, ticket.message)
                }
            }
        }

        return PushResult(sent = sent, invalidTokens = invalidTokens)
    }

    /** 실패하면 null — 로그만 남기고 그 묶음은 보내지 못한 것으로 친다. */
    private suspend fun requestTickets(tokens: List<String>, message: PushMessage): List<ExpoPushTicket>? {
        val body = tokens.map { ExpoPushRequest(to = it, title = message.title, body = message.body, data = message.data) }
        return try {
            val response = httpClient.post(sendUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (accessToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(json.encodeToString(body))
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                logger.warn("Expo 푸시 요청 실패: status={}, body={}", response.status, text.take(500))
                return null
            }
            json.decodeFromString<ExpoPushResponse>(text).data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Expo 푸시 요청 실패: tokens={}", tokens.size, e)
            null
        }
    }

    companion object {
        const val DEFAULT_SEND_URL = "https://exp.host/--/api/v2/push/send"
        const val MAX_MESSAGES_PER_REQUEST = 100
        private const val DEVICE_NOT_REGISTERED = "DeviceNotRegistered"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val logger = LoggerFactory.getLogger(ExpoPushSender::class.java)

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}
