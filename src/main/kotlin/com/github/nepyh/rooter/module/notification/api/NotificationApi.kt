package com.github.nepyh.rooter.module.notification.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun NotificationApi(notificationService: NotificationService) = ApiRoute("notifications") {
    post("device-token") {
        try {
            val request = call.receive<DeviceTokenRegisterRequest>()
            val userId = 1 // 💡 로그인 연동 전 임시 유저
            notificationService.registerDeviceToken(userId, request)
            call.respond(HttpStatusCode.OK, mapOf("message" to "등록되었습니다."))
        } catch (e: NotificationValidationException.InvalidPlatformException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Notification")
        summary = "푸시 알림 디바이스 토큰 등록"
        description = "앱에서 FCM 등으로 발급받은 디바이스 토큰을 등록/갱신. 같은 토큰이 이미 있으면 소유 유저만 갱신"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<DeviceTokenRegisterRequest>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "등록 성공"
            }
            HttpStatusCode.BadRequest {
                description = "platform 이 ANDROID/IOS 가 아님"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
}
