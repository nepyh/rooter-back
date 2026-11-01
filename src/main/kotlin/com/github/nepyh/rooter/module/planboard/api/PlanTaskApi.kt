package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.WeeklyPlanResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun PlanTaskApi(planTaskService: PlanTaskService) = ApiRoute("plan-tasks") {
    authenticate("auth-jwt") {
        get("") {
            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                todayInAppZone()
            }

            val dailyPlan = planTaskService.getDailyPlan(call.userId(), date)
            call.respond(HttpStatusCode.OK, dailyPlan)
        }.describe {
            tag("PlanTask")
            summary = "일일 태스크 목록 조회"
            description = "date 파라미터(yyyy-MM-dd)로 지정한 날짜의 태스크를 조회, 생략 시 오늘 날짜(한국 시간 기준). 본인 플랜보드 기준. 여러 플랜보드의 태스크를 합쳐서 주므로 응답 최상위 dailyPlanId 는 null — 피드백·챗봇 호출에는 각 태스크의 dailyPlanId 를, 어느 보드 소속인지는 각 태스크의 planBoardId 를 사용. isManual = 직접 만든 일정 보드(시험일 없음)의 할일인지 (false = AI 시험 계획 보드)"
            parameters {
                query("date") {
                    description = "조회할 날짜 (yyyy-MM-dd)"
                    required = false
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<DailyPlanResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "date 파라미터 형식이 올바르지 않음 (code=INVALID_DATE_PARAM)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("week") {
            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                todayInAppZone()
            }

            val weeklyPlan = planTaskService.getWeeklyPlan(call.userId(), date)
            call.respond(HttpStatusCode.OK, weeklyPlan)
        }.describe {
            tag("PlanTask")
            summary = "할 일 탭 - 주간 과제 리스트 조회"
            description = "date가 속한 주(월~일)의 요일별 태스크 목록을 조회, date 생략 시 오늘(한국 시간 기준)이 속한 주. 본인 플랜보드 기준(모든 보드 대상). 여러 플랜보드의 태스크를 합쳐서 주므로 응답 최상위 dailyPlanId 는 null — 피드백·챗봇 호출에는 각 태스크의 dailyPlanId 를, 어느 보드 소속인지는 각 태스크의 planBoardId 를 사용. isManual = 직접 만든 일정 보드(시험일 없음)의 할일인지 (false = AI 시험 계획 보드)"
            parameters {
                query("date") {
                    description = "기준 날짜 (yyyy-MM-dd), 이 날짜가 속한 주(월~일)를 반환"
                    required = false
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<WeeklyPlanResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "date 파라미터 형식이 올바르지 않음 (code=INVALID_DATE_PARAM)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("") {
            val request = call.receive<PlanTaskCreateRequest>()
            planTaskService.createTask(call.userId(), request)
            call.respond(HttpStatusCode.Created, PlanTaskCreateResponse("성공적으로 등록되었습니다."))
        }.describe {
            tag("PlanTask")
            summary = "태스크 생성"
            description = "textbookId 를 보내면 그 교과서의 과목을 할일에 붙인다 (조회 응답의 subjectId·subjectName). 안 보내면 과목 미지정(null)"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanTaskCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 플랜보드"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 플랜보드가 아님"
                }
                HttpStatusCode.BadRequest {
                    description = "태스크 이름 오류 (code=INVALID_TASK_NAME), 계획 날짜 형식 오류 (code=INVALID_PLAN_DATE), 시간 형식 오류 (code=INVALID_TIME_FORMAT), " +
                        "종료 시간이 시작 시간보다 빠르거나 같음 (code=INVALID_TIME_RANGE), " +
                        "예상 소요 시간 오류 (code=INVALID_ESTIMATED_MINUTES), 계획 날짜가 플랜보드 기간을 벗어남 (code=PLAN_DATE_OUT_OF_RANGE), " +
                        "또는 존재하지 않는 교과서 (code=INVALID_TEXTBOOK)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("{taskId}") {
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val request = call.receive<PlanTaskUpdateRequest>()

            val response = planTaskService.updateTask(call.userId(), taskId, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("PlanTask")
            summary = "태스크 수정 · 상태 변경"
            description = "taskName/startTime/endTime/estimatedMinutes/status 중 전달된 필드만 수정. 본인 플랜보드 소유 태스크만 가능. " +
                "startTime·endTime 을 바꾸고 estimatedMinutes 를 안 보내면 예상 시간은 바뀐 길이(끝−시작, 분)로 자동으로 맞춘다 (태스크 퀴즈 문항 수·캘린더 공부 시간 기준). " +
                "status 는 사용자가 할 수 있는 전환만 됨: PENDING → FAILED(실패 처리), DONE → PENDING(완료 취소, 태스크 퀴즈로 완료된 태스크는 불가). " +
                "DONE 은 태스크 퀴즈 통과(POST .../quiz/submit)로만 됨. FAILED 는 되돌릴 수 없고, 실패한 태스크에는 태스크 퀴즈가 자동 생성되지 않음. " +
                "지금과 같은 status 는 그대로 성공. 하나라도 거부되면 다른 필드도 바뀌지 않음"
            parameters {
                path("taskId") {
                    description = "태스크 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanTaskUpdateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "수정 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanTaskResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 태스크 이름 오류 (code=INVALID_TASK_NAME), 시간 형식 오류 (code=INVALID_TIME_FORMAT), " +
                        "종료 시간이 시작 시간보다 빠르거나 같음 (code=INVALID_TIME_RANGE), 예상 소요 시간 오류 (code=INVALID_ESTIMATED_MINUTES), " +
                        "status 를 DONE 으로 변경 (code=TASK_QUIZ_REQUIRED), " +
                        "또는 결과가 확정된 태스크(실패 처리 · 퀴즈 통과 / 마지막 차수까지 불합격)의 status·시간·예상 시간 변경이나 DONE → FAILED " +
                        "(code=TASK_FINALIZED, 이름 변경·같은 값 재전송은 허용)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 태스크"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("{taskId}") {
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            planTaskService.deleteTask(call.userId(), taskId)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            tag("PlanTask")
            summary = "태스크 삭제"
            description = "본인 플랜보드의 할일 중 직접 추가한 것(챗봇이 만든 할일 포함)만 삭제할 수 있습니다. AI 가 만든 할일(시험 계획 생성·재조정 보충·퀴즈 복습)은 삭제할 수 없고 400 (code=AI_TASK_NOT_DELETABLE) 으로 응답합니다 — 챗봇으로도 지울 수 없고, 시간 변경·재조정만 됩니다"
            parameters {
                path("taskId") {
                    description = "태스크 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.NoContent {
                    description = "삭제 성공"
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID) / AI 가 만든 할일 (code=AI_TASK_NOT_DELETABLE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 태스크"
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
