package com.github.nepyh.rooter.module.notification.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun NotificationApi(deviceTokenService: DeviceTokenService) = ApiRoute("notifications") {
    authenticate("auth-jwt") {
        post("device-tokens") {
            val request = call.receive<DeviceTokenRegisterRequest>()
            val response = deviceTokenService.register(call.userId(), request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("Notification")
            summary = "기기 푸시 토큰 등록"
            description = "expo-notifications 의 getExpoPushTokenAsync 로 받은 토큰을 등록한다. " +
                "로그인 직후와 앱을 켤 때마다 보내면 된다 — 같은 토큰은 행이 늘지 않고 마지막 사용 시각만 갱신된다. " +
                "다른 계정이 쓰던 토큰이면 지금 계정으로 옮겨진다(같은 폰에서 계정을 바꾼 경우). " +
                "로그아웃(POST /auth/logout)하면 서버가 그 사용자의 토큰을 모두 지우므로 따로 해제할 필요가 없다."
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<DeviceTokenRegisterRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "등록 성공 (새로 등록·갱신 모두)"
                    ContentType.Application.Json {
                        schema = jsonSchema<DeviceTokenResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "토큰 형식 오류 — ExponentPushToken[...] 형식이 아니거나 255자 초과 (code=INVALID_PUSH_TOKEN), " +
                        "또는 platform 이 IOS/ANDROID 가 아니거나 필드 누락 (code=INVALID_REQUEST_BODY)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
