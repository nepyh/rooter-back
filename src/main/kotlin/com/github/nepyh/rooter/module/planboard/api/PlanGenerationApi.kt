package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanGenerationService
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRetryResponse
import com.github.nepyh.rooter.common.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun PlanGenerationApi(planGenerationService: PlanGenerationService) = ApiRoute("plan-generation") {
    authenticate("auth-jwt") {
        post("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<PlanGenerationRequest>()

            val response = planGenerationService.generate(userId, request)
            call.respond(HttpStatusCode.Created, response)
        }.describe {
            tag("PlanGeneration")
            summary = "AI 학습 계획 생성"
            description = "사용자가 직접 지정한 교과서/단원 범위(subjects)를 바탕으로 AI가 시험일까지의 일일 학습 계획을 생성. " +
                "plan_board, plan_subjects, daily_plans, plan_tasks 를 한 번에 생성. 문서 업로드 없이 이미 DB에 있는 교과서/단원 데이터만 사용. " +
                "할일 시간은 수면·등교·불가능 시간과 사용자의 기존 할일(다른 플랜보드 포함, 앞뒤 10분 쉬는 시간)을 피해서 잡음. " +
                "각 할일에는 요청한 과목 중 하나가 subjectId·subjectName 으로 붙음 (AI 가 요청 과목으로 표기하지 못하면 null). " +
                "examEndDate(시험 마지막 날, 선택)는 저장·표시용이라 계획 기간은 시험 첫날(examDate) 전날까지 그대로다. " +
                "[앞 구간 먼저 (#405)] 기간을 7일 안팎 구간으로 나눠 앞 구간만 만들어 응답한다. 기간이 한 구간(7일 이하)이면 전체를 만들고 " +
                "generationStatus=COMPLETED, 더 길면 generationStatus=GENERATING·generatedUntil=앞 구간 마지막 날로 응답하고 " +
                "나머지 구간은 서버가 이어서 만들어 저장한다(구간마다 저장되므로 만들어진 날부터 일일 계획 조회로 보인다). " +
                "진행 상황은 플랜보드 목록의 generationStatus·generatedUntil 로 확인한다. 다 만들면 COMPLETED + 알림 PLAN_GENERATION_COMPLETED, " +
                "재시도까지 실패하면 FAILED + 알림 PLAN_GENERATION_FAILED (POST /plan-generation/{planBoardId}/retry 로 다시 만들기). " +
                "생성 중에는 기간·과목 수정과 아직 안 만든 날의 할일 추가가 409 PLAN_GENERATION_IN_PROGRESS"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanGenerationRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanGenerationResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "제목/날짜/과목 범위 오류 (code: INVALID_TITLE, INVALID_DATE_FORMAT, " +
                        "INVALID_DATE_RANGE — 시작일이 오늘보다 앞이거나, 계획 기간이 80일을 넘거나, 시험일·종료일이 오늘부터 1년을 넘거나 (#401, 사유는 message), 시험 첫날이 시작일 이전이거나, examEndDate 가 있는데 examDate 가 없거나 examDate 보다 빠름, " +
                        "SUBJECTS_REQUIRED, INVALID_SUBJECT_RANGE, MISSING_DATE_INFO)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadGateway {
                    description = "AI 계획 생성 실패 (code: GENERATION_FAILED)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{planBoardId}/retry") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val planBoardId = call.parameters["planBoardId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 planBoardId 입니다."))

            call.respond(HttpStatusCode.Accepted, planGenerationService.retry(userId, planBoardId))
        }.describe {
            tag("PlanGeneration")
            summary = "AI 계획 남은 기간 다시 만들기 (#405)"
            description = "남은 기간을 만들다 재시도까지 실패한(generationStatus=FAILED) 플랜보드의 계획을 다시 만들기 시작한다. " +
                "이미 만든 날(generatedUntil 까지)은 그대로 두고 그 다음 날부터 이어서 만든다. 응답은 바로 오고(GENERATING), " +
                "진행 상황은 플랜보드 목록의 generationStatus·generatedUntil 로 확인한다"
            responses {
                HttpStatusCode.Accepted {
                    description = "다시 만들기 시작"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanGenerationRetryResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 planBoardId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 플랜보드가 아님 (code=FORBIDDEN)"
                }
                HttpStatusCode.NotFound {
                    description = "플랜보드 없음 (code=PLAN_BOARD_NOT_FOUND)"
                }
                HttpStatusCode.Conflict {
                    description = "실패한 계획이 아님 — 생성 중이거나 이미 다 만듦 (code=PLAN_GENERATION_NOT_FAILED)"
                }
            }
        }
    }
}
