package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.example.exampleModule
import com.github.nepyh.rooter.module.health.healthModule
import com.github.nepyh.rooter.module.user.userModule
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

val appModule = module {
    includes(exampleModule)
    includes(healthModule)
    includes(userModule)
}

fun Application.configureAppModule() {
    routing {
        route("api") {
            val koin = getKoin()

            val routeNames = listOf("apiRoute", "exampleApi")

            val apiRoutes = routeNames.map { name ->
                koin.get<ApiRoute>(named(name))
            }

            apiRoutes.forEach { apiRoute ->
                with(apiRoute) { configureRoute() }
            }
        }
    }
}