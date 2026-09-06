package com.github.nepyh.rooter.module.notification.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun NotificationApi(notificationService: NotificationService) = ApiRoute("notifications") {
    authenticate("auth-jwt") {
        post("device-token") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<DeviceTokenRegisterRequest>()
                notificationService.registerDeviceToken(userId, request)
                call.respond(HttpStatusCode.OK, mapOf("message" to "등록되었습니다."))
            } catch (e: NotificationValidationException.InvalidPlatformException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("디바이스 토큰 등록 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Notification")
            summary = "푸시 알림 디바이스 토큰 등록"
            description = "앱에서 발급받은 Expo 푸시 토큰을 등록/갱신. 같은 토큰이 이미 있으면 소유 유저만 갱신"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<DeviceTokenRegisterRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "등록 성공"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "platform 이 ANDROID/IOS 가 아님"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("settings") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            call.respond(HttpStatusCode.OK, notificationService.getSettings(userId))
        }.describe {
            tag("Notification")
            summary = "알림 설정 조회"
            description = "알림 종류별 on/off 설정 조회. 저장된 적 없으면 기본값(전체 켜짐) 반환"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<NotificationSettingsResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("settings") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<NotificationSettingsUpdateRequest>()
                call.respond(HttpStatusCode.OK, notificationService.updateSettings(userId, request))
            } catch (e: Exception) {
                call.application.log.error("알림 설정 변경 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Notification")
            summary = "알림 설정 변경"
            description = "태스크 시작 5분 전 알림 on/off 등, 알림 종류별 설정 변경"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<NotificationSettingsUpdateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "변경 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<NotificationSettingsResponse>()
                    }
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
