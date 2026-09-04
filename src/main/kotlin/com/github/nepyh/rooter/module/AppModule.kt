package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.calendar.CalendarModule
import com.github.nepyh.rooter.module.calendar.exception.CalendarEventNotFoundException
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import com.github.nepyh.rooter.module.example.ExampleModule
import com.github.nepyh.rooter.module.feedback.FeedbackModule
import com.github.nepyh.rooter.module.health.HealthModule
import com.github.nepyh.rooter.module.planboard.PlanBoardModule
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.quiz.QuizModule
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerModule
import com.github.nepyh.rooter.module.school.SchoolModule
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import com.github.nepyh.rooter.module.storage.FileStorageModule
import com.github.nepyh.rooter.module.swagger.SwaggerDocsModule
import com.github.nepyh.rooter.module.user.UserModule
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
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
        PlanBoardModule(),
        QuizModule(appConfig),
        CalendarModule(),
        FeedbackModule()
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
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, "INVALID_REQUEST_BODY", "요청 형식이 올바르지 않습니다.")
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.")
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
