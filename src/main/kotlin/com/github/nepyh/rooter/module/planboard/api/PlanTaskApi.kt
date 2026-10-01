package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCompleteRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCompleteResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.WeeklyPlanResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
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
fun PlanTaskApi(planTaskService: PlanTaskService, taskQuizService: TaskQuizService) = ApiRoute("plan-tasks") {
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
            description = "date 파라미터(yyyy-MM-dd)로 지정한 날짜의 태스크를 조회, 생략 시 오늘 날짜(한국 시간 기준). 본인 플랜보드 기준. 여러 플랜보드의 태스크를 합쳐서 주므로 응답 최상위 dailyPlanId 는 null — 피드백·챗봇 호출에는 각 태스크의 dailyPlanId 를 사용"
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
            description = "date가 속한 주(월~일)의 요일별 태스크 목록을 조회, date 생략 시 오늘(한국 시간 기준)이 속한 주. 본인 플랜보드 기준(모든 보드 대상). 여러 플랜보드의 태스크를 합쳐서 주므로 응답 최상위 dailyPlanId 는 null — 피드백·챗봇 호출에는 각 태스크의 dailyPlanId 를 사용"
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
                        "예상 소요 시간 오류 (code=INVALID_ESTIMATED_MINUTES), 또는 계획 날짜가 플랜보드 기간을 벗어남 (code=PLAN_DATE_OUT_OF_RANGE)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("{taskId}/complete") {
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val request = call.receive<PlanTaskCompleteRequest>()
            val userId = call.userId()

            // 완료는 퀴즈를 통과해야 된다 — true 면 바로 완료하지 않고 풀 퀴즈를 만들어(또는 풀던 퀴즈를) 함께 돌려준다
            val quiz = if (request.isCompleted && !planTaskService.getOwnedTask(userId, taskId).isCompleted) {
                taskQuizService.completeWithQuiz(userId, taskId)
            } else {
                if (!request.isCompleted) planTaskService.completeTask(userId, taskId, false)
                null
            }
            call.respond(HttpStatusCode.OK, planTaskService.getOwnedTask(userId, taskId).withQuiz(quiz))
        }.describe {
            tag("PlanTask")
            summary = "태스크 완료 버튼 (완료 확인 퀴즈 시작) / 완료 취소"
            description = "isCompleted=true(완료 버튼): 바로 완료되지 않고, 응답 quiz 에 풀 완료 확인 퀴즈가 옴 — 퀴즈를 통과하면 태스크가 완료됨. " +
                "퀴즈가 없으면 종료 시각을 기다리지 않고 지금 만들고(AI 생성이라 몇 초), 풀던 퀴즈가 있으면 그걸 줌. " +
                "직전 차수 불합격이면 10분 뒤부터 다음 차수(최초 1회 + 재시도 2회). 이미 퀴즈를 통과한 태스크면 바로 완료되고 quiz 는 null. " +
                "isCompleted=false: 완료 취소 (quiz 는 null). 본인 플랜보드 소유 태스크만 가능"
            parameters {
                path("taskId") {
                    description = "태스크 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanTaskCompleteRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "처리 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanTaskCompleteResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 3번 모두 불합격해 더 풀 수 없음 (code=TASK_QUIZ_NO_MORE_ATTEMPTS), " +
                        "재시도 대기 중 (code=TASK_QUIZ_RETRY_NOT_READY, message 에 남은 분)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 태스크"
                }
                HttpStatusCode.BadGateway {
                    description = "AI 퀴즈 생성 실패 (code=TASK_QUIZ_GENERATION_FAILED) — 잠시 후 다시 누르면 됨"
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
            summary = "태스크 수정"
            description = "taskName/startTime/endTime/estimatedMinutes 중 전달된 필드만 수정. 본인 플랜보드 소유 태스크만 가능"
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
                        "종료 시간이 시작 시간보다 빠르거나 같음 (code=INVALID_TIME_RANGE), 또는 예상 소요 시간 오류 (code=INVALID_ESTIMATED_MINUTES)"
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
            description = "본인 플랜보드 소유 태스크만 가능"
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
                    description = "유효하지 않은 ID (code=INVALID_ID)"
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

private fun PlanTaskResponse.withQuiz(quiz: TaskQuizResponse?) = PlanTaskCompleteResponse(
    id = id,
    dailyPlanId = dailyPlanId,
    taskName = taskName,
    startTime = startTime,
    endTime = endTime,
    estimatedMinutes = estimatedMinutes,
    isCompleted = isCompleted,
    quiz = quiz
)
