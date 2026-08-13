package com.github.nepyh.rooter

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.auth.JwtValidator
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.common.database.DatabaseConfig
import com.github.nepyh.rooter.common.database.DatabaseManager
import com.github.nepyh.rooter.module.AppModule
import com.github.nepyh.rooter.module.configureAppModule
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
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
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "인증이 필요합니다."))
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