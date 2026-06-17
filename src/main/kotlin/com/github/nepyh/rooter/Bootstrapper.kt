package com.github.nepyh.rooter

import com.github.nepyh.rooter.module.appModule
import com.github.nepyh.rooter.module.database.DatabaseConfig
import com.github.nepyh.rooter.module.database.DatabaseManager
import com.github.nepyh.rooter.module.configureAppModule
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger


fun Application.devModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
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
        anyHost()
    }

    serverConfig {
        developmentMode = true
    }

    DatabaseManager.init(
        DatabaseConfig(
            driverClassName = "org.postgresql.Driver",
            jdbcUrl = environment.config.property("storage.jdbcUrl").getString(),
            username = environment.config.property("storage.dbUser").getString(),
            password = environment.config.property("storage.dbPassword").getString(),
            maxPoolSize = environment.config.property("storage.dbMaxPoolSize").getString().toInt()
        )
    )
    configureAppModule()
}


fun Application.prodModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }

    install(ContentNegotiation) {
        json()
    }

    install(CORS) { // TODO env!!!!!!!!!
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }

    serverConfig {
        developmentMode = false
    }

    DatabaseManager.init(
        DatabaseConfig(
            driverClassName = "org.postgresql.Driver",
            jdbcUrl = environment.config.property("storage.jdbcUrl").getString(),
            username = environment.config.property("storage.dbUser").getString(),
            password = environment.config.property("storage.dbPassword").getString(),
            maxPoolSize = environment.config.property("storage.dbMaxPoolSize").getString().toInt()
        )
    )
    configureAppModule()
}
