package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.calendar.CalendarModule
import com.github.nepyh.rooter.module.calendar.exception.CalendarEventNotFoundException
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import com.github.nepyh.rooter.module.chat.ChatModule
import com.github.nepyh.rooter.module.example.ExampleModule
import com.github.nepyh.rooter.module.feedback.FeedbackModule
import com.github.nepyh.rooter.module.health.HealthModule
import com.github.nepyh.rooter.module.leveltest.LevelTestModule
import com.github.nepyh.rooter.module.planboard.PlanBoardModule
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanSubjectNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.BusyTimeValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.quiz.QuizModule
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerModule
import com.github.nepyh.rooter.module.school.SchoolModule
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import com.github.nepyh.rooter.module.storage.FileStorageModule
import com.github.nepyh.rooter.module.studystyle.StudyStyleModule
import com.github.nepyh.rooter.module.swagger.SwaggerDocsModule
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.exception.DailyPlanNotFoundException as ChatDailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException as FeedbackDailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestNotFoundException
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestValidationException
import com.github.nepyh.rooter.module.quiz.exception.QuizNotFoundException
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.studystyle.exception.StudyStyleValidationException
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizNotFoundException
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizValidationException
import com.github.nepyh.rooter.module.taskquiz.TaskQuizModule
import com.github.nepyh.rooter.module.review.ReviewModule
import com.github.nepyh.rooter.module.review.exception.ReviewQuestionNotFoundException
import com.github.nepyh.rooter.module.review.exception.ReviewValidationException
import com.github.nepyh.rooter.module.user.UserModule
import com.github.nepyh.rooter.module.user.exception.UnavailableTimeNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.inject

fun AppModule(appConfig: AppConfig): Module = module {
    // dev-related modules
    if (appConfig.environment == EnvironmentMode.DEV) {
        includes(
            ExampleModule(),
            SwaggerDocsModule()
        )
    }
    // infra-related modules
    includes(
        HealthModule(),
        FileStorageModule(appConfig),
        SchedulerModule(),
        SchoolModule(appConfig)
    )
    // service-related modules
    includes(
        UserModule(appConfig),
        PlanBoardModule(appConfig),
        QuizModule(appConfig),
        CalendarModule(),
        FeedbackModule(appConfig),
        LevelTestModule(appConfig),
        StudyStyleModule(),
        TaskQuizModule(appConfig),
        ReviewModule(),
        ChatModule(appConfig)
    )

    single<List<ApiRoute>> { getAll() }
}

fun Application.configureAppModule() {
    val apiRoutes: List<ApiRoute> by inject()
    routing {
        route("api") {
            apiRoutes.forEach { apiRoute ->
                with(apiRoute) { configureRoute() }
            }
        }
    }

    install(StatusPages) {
        exception<UserNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "USER_NOT_FOUND", cause.message)
        }
        exception<UnavailableTimeNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "UNAVAILABLE_TIME_NOT_FOUND", cause.message)
        }
        exception<UserValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<NiceApiException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<CalendarEventNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "CALENDAR_EVENT_NOT_FOUND", cause.message)
        }
        exception<CalendarValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<PlanBoardNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "PLAN_BOARD_NOT_FOUND", cause.message)
        }
        exception<PlanBoardValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<PlanBoardForbiddenException> { call, cause ->
            call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN", cause.message)
        }
        exception<PlanTaskValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<BusyTimeValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<PlanTaskNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "PLAN_TASK_NOT_FOUND", cause.message)
        }
        exception<PlanSubjectNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "PLAN_SUBJECT_NOT_FOUND", cause.message)
        }
        exception<ChatDailyPlanNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "DAILY_PLAN_NOT_FOUND", cause.message)
        }
        exception<FeedbackDailyPlanNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "DAILY_PLAN_NOT_FOUND", cause.message)
        }
        exception<ChatValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<FeedbackNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "FEEDBACK_NOT_FOUND", cause.message)
        }
        exception<FeedbackAlreadySubmittedException> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, "FEEDBACK_ALREADY_SUBMITTED", cause.message)
        }
        exception<FeedbackValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<QuizNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "QUIZ_NOT_FOUND", cause.message)
        }
        exception<QuizValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<TaskQuizNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "TASK_QUIZ_NOT_FOUND", cause.message)
        }
        exception<TaskQuizValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<ReviewQuestionNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "REVIEW_QUESTION_NOT_FOUND", cause.message)
        }
        exception<ReviewValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<LevelTestNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "LEVEL_TEST_NOT_FOUND", cause.message)
        }
        exception<LevelTestValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<StudyStyleValidationException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<BadRequestException> { call, cause ->
            // cause.cause 에 kotlinx.serialization 상세(누락 필드·문법 위치)가 들어 있다.
            call.application.log.warn("Invalid request body: ${cause.cause?.message ?: cause.message}")
            call.respondError(HttpStatusCode.BadRequest, "INVALID_REQUEST_BODY", "요청 형식이 올바르지 않습니다.")
        }
        status(HttpStatusCode.NotFound) { _ ->
            // 라우팅이 붙인 404 는 본문이 HttpStatusCodeContent 라 code 가 없다.
            // 도메인 예외로 만든 404(JSON)는 그대로 둔다.
            if (content is HttpStatusCodeContent) {
                call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.")
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.")
        }
    }
    
    // Ktor 엔진이 ApplicationPhase.Plugins 에 기본 transformation checker 를 설치하고
    // ContentTransformationException 을 직접 catch 해 415 + 내부 메시지(text/plain)로 응답한다.
    // StatusPages 는 BeforeFallback 단계라 이 예외에 도달하지 못하므로 같은 Plugins 단계에서
    // 먼저 잡아 에러 응답 계약(ErrorResponse)으로 정규화한다.
    intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        try {
            proceed()
        } catch (cause: ContentTransformationException) {
            when (cause) {
                is PayloadTooLargeException -> call.respondError(
                    HttpStatusCode.PayloadTooLarge,
                    "PAYLOAD_TOO_LARGE",
                    "요청 본문이 너무 큽니다."
                )
                else -> call.respondError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST_BODY",
                    "요청 본문을 해석할 수 없습니다. Content-Type: application/json 인지 확인해 주세요."
                )
            }
        }
    }

    val schedulerEngine: SchedulerEngine by inject()
    schedulerEngine.start(this)
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String?
) {
    respond(status, ErrorResponse(code, message ?: "알 수 없는 오류입니다."))
}
