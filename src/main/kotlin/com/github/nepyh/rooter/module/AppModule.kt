package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.blink.blinkModule
import io.ktor.server.application.*
import io.ktor.server.routing.routing
import org.koin.dsl.module
import org.koin.ktor.ext.inject

val appModule = module {
    includes(blinkModule)
}

fun Application.configureAppModule() {
    val apiRoutes by inject<List<ApiRoute>>()

    routing {
        apiRoutes.forEach { apiRoute ->
            with(apiRoute) { configureRoute() }
        }
    }
}
