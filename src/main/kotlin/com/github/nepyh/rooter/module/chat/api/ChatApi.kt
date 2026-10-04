package com.github.nepyh.rooter.module.chat.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.chat.ChatService
import com.github.nepyh.rooter.module.chat.dto.ChatMessageRequest
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun ChatApi(chatService: ChatService) = ApiRoute("daily-plans") {
    authenticate("auth-jwt") {
        post("/{dailyPlanId}/chat/message") {
            val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
            if (dailyPlanId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 dailyPlanId 입니다."))
                return@post
            }

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<ChatMessageRequest>()
            val response = chatService.sendMessage(userId, dailyPlanId, request.message)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("Chat")
            summary = "챗봇에게 메시지 보내기 (계획 재조정)"
            description = "갑자기 생긴 일정을 대화로 알려주면 AI가 그 날의 태스크를 현실적으로 재조정한다 " +
                "(예: \"오늘 5~10시까지 할머니댁 가야 해\"). 계획과 무관한 질문/잡담이면 계획은 그대로 두고 답변만 한다. " +
                "실제 시각 배정은 서버가 결정론적으로 계산하며(PlanTaskScheduler, plan-generation 과 동일 로직), " +
                "AI 는 태스크 이름/소요시간과 새로 생긴 공부 불가능 시간(busy window)만 판단한다. " +
                "\"오늘 거 내일로 미뤄줘\" 처럼 날짜를 옮기는 요청이면 해당 할일을 그날(날짜를 안 말하면 다음 날) 빈 시간으로 옮기고 " +
                "movedTasks 로 돌려준다(옮겨간 날의 dailyPlanId·시각). updatedTasks 는 대화한 날에 남은 할일. " +
                "바로 전날에서 미뤄져 온 할일은 이틀 연속 미룰 수 없고, 플랜보드 기간 밖·과거 날짜로도 미룰 수 없다 (이때 planChanged=false, reply 로 이유 안내)"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ChatMessageRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "처리 성공 (계획이 안 바뀌었으면 updatedTasks 는 null, 미루지 않았으면 movedTasks 는 null)"
                    ContentType.Application.Json {
                        schema = jsonSchema<ChatMessageResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 dailyPlanId (code=INVALID_ID), 또는 메시지가 비어있음 (code=CHAT_001)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("/{dailyPlanId}/chat") {
            val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
            if (dailyPlanId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 dailyPlanId 입니다."))
                return@get
            }

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val history = chatService.getHistory(userId, dailyPlanId)
            call.respond(HttpStatusCode.OK, history)
        }.describe {
            tag("Chat")
            summary = "챗봇 대화 이력 조회"
            description = "해당 일일 계획에 쌓인 대화 이력을 시간순으로 조회. 본인 소유의 일일 계획만 조회 가능"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<ChatTurnResponse>>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 dailyPlanId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
