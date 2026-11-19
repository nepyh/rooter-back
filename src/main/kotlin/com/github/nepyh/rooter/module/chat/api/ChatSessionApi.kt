package com.github.nepyh.rooter.module.chat.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.chat.ChatService
import com.github.nepyh.rooter.module.chat.ChatSessionService
import com.github.nepyh.rooter.module.chat.dto.ChatMessageRequest
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatSessionResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnFeedbackRequest
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi

/**
 * 챗봇 세션·메시지 관리 (#342). 계획을 보며 보내는 메시지는 POST /daily-plans/{dailyPlanId}/chat/message,
 * 계획 없이 보내는 메시지는 POST /chat/message (#351)
 */
@OptIn(ExperimentalKtorApi::class)
fun ChatSessionApi(chatSessionService: ChatSessionService, chatService: ChatService) = ApiRoute("chat") {
    authenticate("auth-jwt") {
        post("message") {
            val request = call.receive<ChatMessageRequest>()
            call.respond(HttpStatusCode.OK, chatService.sendMessageWithoutPlan(call.userId(), request.message, request.sessionId))
        }.describe {
            tag("Chat")
            summary = "챗봇에게 메시지 보내기 (계획 없이)"
            description = "보고 있는 계획이 없을 때 챗봇과 대화한다 (#351). 교과서·수업 내용 질문과 잡담에 답하고, 계획은 바꾸지 않는다 — " +
                "할일 미루기·추가나 일정 반영을 부탁하면 플랜보드에서 계획을 먼저 만들라고 안내한다. 계획이 있으면 POST /daily-plans/{dailyPlanId}/chat/message 를 쓴다. " +
                "body 는 그 API 와 같다. sessionId 세션에 이어 붙이고(AI 는 그 세션 대화만 기억), 생략하면 새 세션에 저장한다. " +
                "응답도 같은 형태이고 planChanged 는 항상 false, updatedTasks·movedTasks·updatedDays 는 null. 저장된 대화의 dailyPlanId 는 null"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ChatMessageRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "처리 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ChatMessageResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "메시지가 비어있음 (code=CHAT_001), 요청 형식이 틀림 (code=INVALID_REQUEST_BODY)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 세션 (code=CHAT_SESSION_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("sessions") {
            call.respond(HttpStatusCode.OK, chatSessionService.listSessions(call.userId()))
        }.describe {
            tag("Chat")
            summary = "내 챗봇 세션 목록"
            description = "날짜와 상관없는 내 세션 전체. 마지막으로 대화한 세션(updatedAt)부터. 제목(title)은 세션 첫 메시지를 보낼 때 AI 가 정하고, 그전엔 null"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<ChatSessionResponse>>()
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

        post("sessions") {
            call.respond(HttpStatusCode.Created, chatSessionService.createSession(call.userId()))
        }.describe {
            tag("Chat")
            summary = "새 챗봇 세션 만들기"
            description = "빈 대화로 시작하는 새 세션. 챗봇은 이 세션의 대화만 기억한다. 요청 body 없음. " +
                "만든 세션 id 를 POST /daily-plans/{dailyPlanId}/chat/message (계획 없이는 POST /chat/message) 의 sessionId 로 보내면 이 세션에 쌓인다 (title 은 첫 메시지에서 정해짐)"
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공 (title 은 null)"
                    ContentType.Application.Json {
                        schema = jsonSchema<ChatSessionResponse>()
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

        delete("sessions") {
            val deleted = chatSessionService.deleteAllSessions(call.userId())
            call.respond(HttpStatusCode.OK, mapOf("deletedSessionCount" to deleted))
        }.describe {
            tag("Chat")
            summary = "챗봇 세션 전체 삭제"
            description = "내 세션과 그 안의 대화를 전부 지운다. 챗봇이 이미 바꾼 계획(할일)은 되돌리지 않는다. " +
                "요청 body 없음. 응답 deletedSessionCount 는 지운 세션 수 (세션이 없으면 0, 그래도 200)"
            responses {
                HttpStatusCode.OK {
                    description = "삭제 성공 ({\"deletedSessionCount\": 3})"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("sessions/{sessionId}") {
            val sessionId = call.parameters["sessionId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 sessionId 입니다."))
            chatSessionService.deleteSession(call.userId(), sessionId)
            call.respond(HttpStatusCode.OK, mapOf("message" to "삭제되었습니다."))
        }.describe {
            tag("Chat")
            summary = "챗봇 세션 삭제"
            description = "세션과 그 안의 대화를 모두 지운다. 챗봇이 이미 바꾼 계획(할일)은 되돌리지 않는다. 본인 세션만"
            parameters {
                path("sessionId") {
                    description = "세션 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "삭제 성공"
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 sessionId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 세션 (code=CHAT_SESSION_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("sessions/{sessionId}/turns") {
            val sessionId = call.parameters["sessionId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 sessionId 입니다."))
            call.respond(HttpStatusCode.OK, chatSessionService.getSessionTurns(call.userId(), sessionId))
        }.describe {
            tag("Chat")
            summary = "챗봇 세션 대화 조회"
            description = "세션의 대화를 시간순으로. 항목의 id 로 좋아요·싫어요(챗봇 답변만)·삭제를 하고, " +
                "dailyPlanId 는 그 메시지로 어느 날 계획을 보고 고쳤는지다 (계획 없이 나눈 대화면 null). feedback 은 챗봇 답변의 LIKE / DISLIKE / null. 본인 세션만"
            parameters {
                path("sessionId") {
                    description = "세션 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<ChatTurnResponse>>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 sessionId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 세션 (code=CHAT_SESSION_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        put("turns/{turnId}/feedback") {
            val turnId = call.parameters["turnId"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 turnId 입니다."))
            val request = call.receive<ChatTurnFeedbackRequest>()
            call.respond(HttpStatusCode.OK, chatSessionService.setFeedback(call.userId(), turnId, request.feedback))
        }.describe {
            tag("Chat")
            summary = "챗봇 답변 좋아요·싫어요"
            description = "챗봇 답변(role=assistant)에 LIKE 또는 DISLIKE 를 남긴다. 같은 답변에 다시 보내면 덮어쓰고, feedback 을 null 로 보내면 취소. " +
                "사용자 메시지에는 남길 수 없다. 응답은 바뀐 메시지"
            parameters {
                path("turnId") {
                    description = "메시지 ID (대화 조회 항목의 id, 또는 메시지 보내기 응답의 assistantTurnId)"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ChatTurnFeedbackRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "저장 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ChatTurnResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 turnId (code=INVALID_ID), 사용자 메시지에 남기려 함 (code=CHAT_FEEDBACK_NOT_ALLOWED), " +
                        "feedback 이 LIKE·DISLIKE·null 이 아님 (code=INVALID_REQUEST_BODY)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 메시지 (code=CHAT_TURN_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("turns/{turnId}") {
            val turnId = call.parameters["turnId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 turnId 입니다."))
            val deletedIds = chatSessionService.deleteTurn(call.userId(), turnId)
            call.respond(HttpStatusCode.OK, mapOf("deletedTurnIds" to deletedIds))
        }.describe {
            tag("Chat")
            summary = "챗봇 메시지 삭제 (질문·답 한 쌍)"
            description = "사용자 메시지를 지우면 그에 대한 챗봇 답변도 같이 지운다 (챗봇 답변 id 를 줘도 그 질문과 함께 지움). " +
                "지운 대화는 이후 챗봇이 기억하지 않는다. 챗봇이 이미 바꾼 계획(할일)은 되돌리지 않는다. " +
                "응답 deletedTurnIds 는 실제로 지운 메시지 id (1~2개). 본인 메시지만"
            parameters {
                path("turnId") {
                    description = "메시지 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "삭제 성공 ({\"deletedTurnIds\": [12, 13]})"
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 turnId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 메시지 (code=CHAT_TURN_NOT_FOUND)"
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
