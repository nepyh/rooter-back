package com.github.nepyh.rooter

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.nepyh.rooter.common.auth.JwtValidator
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.common.database.DatabaseConfig
import com.github.nepyh.rooter.common.database.DatabaseManager
import com.github.nepyh.rooter.module.AppModule
import com.github.nepyh.rooter.module.configureAppModule
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger


fun Application.appEntryModule() {
    val appConfig = AppConfig.fromApplicationConfig(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(AppModule(appConfig))
    }

    install(ContentNegotiation) {
        json()
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

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        if (appConfig.environment == EnvironmentMode.PROD) {
            appConfig.corsAllowedHosts.forEach { hostName ->
                allowHost(hostName, schemes = listOf("http", "https"))
            }
        } else {
            anyHost()
        }

        allowCredentials = true
    }

    val jwtValidator: JwtValidator by inject()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = appConfig.jwtIssuer
            verifier(
                JWT.require(Algorithm.HMAC256(appConfig.jwtSecret))
                    .withIssuer(appConfig.jwtIssuer)
                    .build()
            )
            validate { credential -> jwtValidator.validate(credential) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "인증이 필요합니다."))
            }
        }
    }

    DatabaseManager.init(
        DatabaseConfig(
            driverClassName = "org.postgresql.Driver",
            jdbcUrl = appConfig.jdbcUrl,
            username = appConfig.dbUsername,
            password = appConfig.dbPassword,
            maxPoolSize = appConfig.dbMaxPoolSize
        )
    )

    configureAppModule()
}