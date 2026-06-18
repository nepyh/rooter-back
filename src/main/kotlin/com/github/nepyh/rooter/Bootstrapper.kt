package com.github.nepyh.rooter

import com.github.nepyh.rooter.config.AppConfig
import com.github.nepyh.rooter.config.EnvironmentMode
import com.github.nepyh.rooter.module.AppModule
import com.github.nepyh.rooter.module.configureAppModule
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
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

    configureAppModule()
}
