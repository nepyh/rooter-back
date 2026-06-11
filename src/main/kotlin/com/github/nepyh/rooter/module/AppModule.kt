package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.example.exampleModule
import com.github.nepyh.rooter.module.health.healthModule
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.dsl.module
import org.koin.ktor.ext.inject


val appModule = module {
    includes(exampleModule)
    includes(healthModule)

    single<List<ApiRoute>> { getAll() }
}

fun Application.configureAppModule() {
    val apiRoutes by inject<List<ApiRoute>>()

    routing {
        route("api") {
            apiRoutes.forEach { apiRoute ->
                with(apiRoute) { configureRoute() }
            }
        }
    }
}
