package com.github.nepyh.rooter

import com.github.nepyh.rooter.module.appModule
import com.github.nepyh.rooter.module.database.DatabaseConfig
import com.github.nepyh.rooter.module.database.DatabaseManager
import com.github.nepyh.rooter.module.installAppModule
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.devModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
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
    installAppModule()
}

fun Application.prodModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
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
    installAppModule()
}

// You can swap only dataModule in each entry module (actually, it's a function)