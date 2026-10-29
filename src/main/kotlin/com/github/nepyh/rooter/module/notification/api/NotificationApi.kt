package com.github.nepyh.rooter.module.notification.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.NOTIFICATION_DEFAULT_PAGE_SIZE
import com.github.nepyh.rooter.module.notification.NOTIFICATION_MAX_PAGE_SIZE
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.NotificationSettingService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationListResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.dto.ReadAllResponse
import com.github.nepyh.rooter.module.notification.dto.UnreadCountResponse
import com.github.nepyh.rooter.module.notification.exception.NotificationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
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
fun NotificationApi(
    deviceTokenService: DeviceTokenService,
    notificationService: NotificationService,
    notificationSettingService: NotificationSettingService
) = ApiRoute("notifications") {
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

        get("") {
            val cursor = call.request.queryParameters["cursor"]
                ?.let { it.toIntOrNull() ?: throw NotificationException.InvalidPageException() }
            val limit = call.request.queryParameters["limit"]
                ?.let { it.toIntOrNull()?.takeIf { n -> n in 1..NOTIFICATION_MAX_PAGE_SIZE } ?: throw NotificationException.InvalidPageException() }
                ?: NOTIFICATION_DEFAULT_PAGE_SIZE
            call.respond(HttpStatusCode.OK, notificationService.list(call.userId(), cursor, limit))
        }.describe {
            tag("Notification")
            summary = "알림함 목록"
            description = "본인 알림을 최신순으로 돌려준다. 푸시 설정을 꺼도 알림은 여기에 남는다. " +
                "다음 페이지는 응답의 nextCursor 를 cursor 로 넘겨 받는다 (nextCursor=null 이면 마지막). " +
                "type 으로 눌렀을 때 이동할 화면을 고르고, 이동 대상은 data 에 있다 (TASK_REMINDER → data.planTaskId)"
            parameters {
                query("cursor") {
                    description = "앞 페이지 응답의 nextCursor. 생략하면 첫 페이지"
                    required = false
                    schema = jsonSchema<Int>()
                }
                query("limit") {
                    description = "한 페이지 개수 1~$NOTIFICATION_MAX_PAGE_SIZE. 생략하면 $NOTIFICATION_DEFAULT_PAGE_SIZE"
                    required = false
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<NotificationListResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "cursor 가 정수가 아니거나 limit 이 1~$NOTIFICATION_MAX_PAGE_SIZE 밖 (code=NOTIFICATION_INVALID_PAGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
            }
        }

        get("unread-count") {
            call.respond(HttpStatusCode.OK, notificationService.unreadCount(call.userId()))
        }.describe {
            tag("Notification")
            summary = "안 읽은 알림 개수"
            description = "앱 아이콘·탭 배지용. 알림함 목록을 받지 않고 개수만 확인할 때 쓴다"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<UnreadCountResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
            }
        }

        patch("{id}/read") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            call.respond(HttpStatusCode.OK, notificationService.markRead(call.userId(), id))
        }.describe {
            tag("Notification")
            summary = "알림 하나 읽음 처리"
            description = "알림함에서 항목을 눌렀을 때, 또는 푸시를 눌러 앱이 열렸을 때 부른다 (푸시 data.notificationId 가 이 id). " +
                "이미 읽은 알림이면 처음 읽은 시각을 그대로 두고 200 을 돌려준다"
            parameters {
                path("id") {
                    description = "알림 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "읽음 처리된 알림"
                    ContentType.Application.Json {
                        schema = jsonSchema<NotificationResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "id 가 정수가 아님 (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "없는 알림이거나 다른 사용자의 알림 (code=NOTIFICATION_NOT_FOUND)"
                }
            }
        }

        patch("read-all") {
            call.respond(HttpStatusCode.OK, notificationService.markAllRead(call.userId()))
        }.describe {
            tag("Notification")
            summary = "알림 전부 읽음 처리"
            description = "안 읽은 알림을 모두 읽음으로 바꾸고 바뀐 개수를 돌려준다"
            responses {
                HttpStatusCode.OK {
                    description = "처리 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ReadAllResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
            }
        }

        get("settings") {
            call.respond(HttpStatusCode.OK, notificationSettingService.get(call.userId()))
        }.describe {
            tag("Notification")
            summary = "알림 설정 조회"
            description = "한 번도 바꾸지 않았으면 기본값(할일 알림 켜짐, 시작 5분 전)을 돌려준다"
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
            }
        }

        patch("settings") {
            val request = call.receive<NotificationSettingsUpdateRequest>()
            call.respond(HttpStatusCode.OK, notificationSettingService.update(call.userId(), request))
        }.describe {
            tag("Notification")
            summary = "알림 설정 수정"
            description = "보낸 필드만 바꾸고 바뀐 전체 설정을 돌려준다. taskReminderEnabled=false 면 할일 시작 전 푸시가 오지 않지만 알림함에는 남는다"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<NotificationSettingsUpdateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "수정 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<NotificationSettingsResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "taskReminderLeadMinutes 가 0~60 밖 (code=INVALID_LEAD_MINUTES), 또는 요청 본문 형식 오류 (code=INVALID_REQUEST_BODY)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
            }
        }
    }
}

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
