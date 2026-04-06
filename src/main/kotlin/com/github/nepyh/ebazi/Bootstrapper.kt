package com.github.nepyh.ebazi

import com.github.nepyh.ebazi.module.appModule
import com.github.nepyh.ebazi.module.dataModule
import com.github.nepyh.ebazi.module.installAppModule
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.routing.routing
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.devModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule, dataModule)
    }

    serverConfig {
        developmentMode = true
    }

    installAppModule()
}

fun Application.prodModule() {
    install(Koin) {
        slf4jLogger()
        modules(appModule, dataModule)
    }

    serverConfig {
        developmentMode = false
    }

    installAppModule()
}

// You can swap only dataModule in each entry module (actually, it's a function)