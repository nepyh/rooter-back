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
            val response = chatService.sendMessage(userId, dailyPlanId, request.message, request.sessionId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("Chat")
            summary = "챗봇에게 메시지 보내기 (계획 재조정)"
            description = "대화는 세션(#342)에 쌓인다 — 요청의 sessionId 세션에 이어 붙이고 AI 는 그 세션 대화만 기억한다(새 세션은 빈 대화로 시작). " +
                "sessionId 를 생략하면 이 날 계획으로 마지막에 대화한 세션, 그것도 없으면 새 세션에 저장한다(예전 앱 호환). 세션은 날짜와 상관없는 유저 전체 목록이고, " +
                "경로의 dailyPlanId 는 이 메시지로 어느 날 계획을 보고 고칠지다. 응답의 sessionId·sessionTitle 은 저장된 세션과 그 제목(세션 첫 메시지에서 AI 가 정함), " +
                "userTurnId·assistantTurnId 는 저장된 메시지 id(좋아요·싫어요·삭제용). " +
                "교과서·수업 내용(개념·공식·풀이 방법)을 물으면 학년과 플랜보드 교과서 단원에 맞춰 설명하고 계획은 그대로 둔다(planChanged=false). " +
                "갑자기 생긴 일정을 대화로 알려주면 AI가 그 날의 태스크를 현실적으로 재조정한다 " +
                "(예: \"오늘 5~10시까지 할머니댁 가야 해\"). 계획과 무관한 질문/잡담이면 계획은 그대로 두고 답변만 한다. " +
                "실제 시각 배정은 서버가 결정론적으로 계산하며(PlanTaskScheduler, plan-generation 과 동일 로직), " +
                "AI 는 태스크 이름/소요시간과 새로 생긴 공부 불가능 시간(busy window)만 판단한다. " +
                "\"오늘 거 내일로 미뤄줘\" 처럼 날짜를 옮기는 요청이면 해당 할일을 그날(날짜를 안 말하면 다음 날) 빈 시간으로 옮기고 " +
                "movedTasks 로 돌려준다(옮겨간 날의 dailyPlanId·시각). updatedTasks 는 대화한 날에 남은 할일. " +
                "바로 전날에서 미뤄져 온 할일은 이틀 연속 미룰 수 없고, 플랜보드 기간 밖·과거 날짜로도 미룰 수 없다 (이때 planChanged=false, reply 로 이유 안내). " +
                "그날 할일을 직접 고치는 요청도 된다 — \"수학 30분으로 줄여줘\"(줄이기·늘리기), \"8시에 과학 추가해줘\"(추가, 플랜보드 학습 범위로 이름·과목을 붙임), " +
                "\"영어 빼줘\"(삭제), \"수학 9시로 옮겨줘\"(특정 시각으로 옮기기). 그 시간이 수면·등교·불가능 시간·다른 할일·지난 시간과 겹치거나, " +
                "퀴즈를 시작한 할일 삭제·결과가 확정된 할일 시간 변경이면 그 편집은 거절하고 reply 로 이유와 그날 빈 시간을 안내한다. 하나라도 적용되면 planChanged=true, updatedTasks 는 그날 할일 전체. " +
                "다른 날에도 걸린 일정이면(\"이번 주 수·목 저녁 7~9시 학원\", \"다음 주 내내 8시 이후엔 못 해\", \"매주 수요일 학원\" — 플랜보드 기간까지 최대 31일) " +
                "그 날들의 같은 플랜보드 계획에서 겹치는 할일만 원래 시각 이후 빈 시간으로 옮기고(이름·길이 그대로, AI 가 만든 할일·완료한 할일은 안 옮김, 빈 시간이 없으면 그대로 둠) " +
                "updatedDays 로 돌려준다(날짜·dailyPlanId·그날 할일 전체, 실제로 옮긴 날만). 대화한 날이 안 바뀌었으면 updatedTasks 는 null. " +
                "그날 계획이 없거나 겹치는 할일이 없는 날, 지난 날·플랜보드 기간 밖인 날은 reply 로 그대로라고 안내한다. 반복 일정을 앞으로의 계획 생성에도 반영하려면 불가능 시간(unavailable-times)으로 등록해야 한다. " +
                "AI 는 플랜보드 기간 전체의 날짜별 할일을 보고 답한다 (#337) — \"목요일에 뭐 해?\" 같은 질문, " +
                "\"목요일 수학 30분으로 줄여줘\"·\"주말에 과학 1시간씩 추가해줘\"·\"이번 주 너무 많아, 줄여줘\" 처럼 다른 날 할일 편집(줄이기·늘리기·추가·삭제·시각 옮기기), " +
                "\"목요일 영어 금요일로 미뤄줘\" 처럼 다른 날 할일 미루기도 된다. 고칠 수 있는 날은 오늘부터 플랜보드 끝까지이고 지난 날은 reply 로 안내만 한다. " +
                "다른 날이 바뀌면 updatedDays 에 그날 할일 전체가 들어가고(계획이 없던 날에 추가하면 그날 계획을 새로 만든다), 대화한 날이 바뀌었을 때만 updatedTasks 가 채워진다"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ChatMessageRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "처리 성공 (대화한 날 계획이 안 바뀌었으면 updatedTasks 는 null, 미루지 않았으면 movedTasks 는 null, 다른 날을 안 바꿨으면 updatedDays 는 null. planChanged 는 어느 날이든 바뀌었으면 true)"
                    ContentType.Application.Json {
                        schema = jsonSchema<ChatMessageResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 dailyPlanId (code=INVALID_ID), 메시지가 비어있음 (code=CHAT_001), 요청 형식이 틀림 (code=INVALID_REQUEST_BODY)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND), 존재하지 않거나 본인 소유가 아닌 세션 (code=CHAT_SESSION_NOT_FOUND)"
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
            summary = "챗봇 대화 이력 조회 (날짜별)"
            description = "해당 일일 계획을 보고 나눈 대화를 세션 상관없이 시간순으로 조회. 본인 소유의 일일 계획만 조회 가능. " +
                "세션별 대화는 GET /chat/sessions/{sessionId}/turns (#342). 항목의 id 로 좋아요·싫어요·삭제를 한다"
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
