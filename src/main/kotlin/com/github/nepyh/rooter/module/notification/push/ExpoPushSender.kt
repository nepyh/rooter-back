package com.github.nepyh.rooter.module.notification.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

private const val EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send"

@Serializable
private data class ExpoPushMessage(
    val to: String,
    val title: String,
    val body: String
)

// Expo 가 내부적으로 FCM/APNs 로 중계해줘서, 서버는 Firebase/Apple 자격증명 없이 이 HTTP API 만 호출하면 됨
class ExpoPushSender : PushSender {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    override suspend fun send(deviceToken: String, title: String, body: String) {
        client.post(EXPO_PUSH_URL) {
            contentType(ContentType.Application.Json)
            setBody(ExpoPushMessage(to = deviceToken, title = title, body = body))
        }
    }
}
