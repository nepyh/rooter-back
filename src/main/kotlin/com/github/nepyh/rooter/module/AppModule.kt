package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.example.ExampleModule
import com.github.nepyh.rooter.module.health.HealthModule
import com.github.nepyh.rooter.module.storage.FileStorageModule
import com.github.nepyh.rooter.module.swagger.SwaggerDocsModule
import com.github.nepyh.rooter.module.user.UserModule
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.ContentTransformationException
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
        FileStorageModule(appConfig)
    )
    // service-related modules
    includes(
        UserModule(appConfig)
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
            call.respond(HttpStatusCode.NotFound, mapOf("message" to cause.message))
        }
        exception<UserValidationException.BadCredentialsException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("code" to "BAD_CREDENTIALS", "message" to cause.message))
        }
        exception<UserValidationException.DuplicatedEmailException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, mapOf("message" to cause.message))
        }
        exception<UserValidationException.WrongUsernameException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to cause.message))
        }
        exception<UserValidationException.WrongPasswordFormatException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to cause.message))
        }
        exception<UserValidationException.WrongEmailLengthException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "EMAIL_TOO_LONG", "message" to cause.message))
        }
        exception<UserValidationException.WrongSchoolIdException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "INVALID_SCHOOL_ID", "message" to cause.message))
        }
        exception<UserValidationException.WrongDayOfWeekException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "INVALID_DAY_OF_WEEK", "message" to cause.message))
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "요청 형식이 올바르지 않습니다."))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }
}
