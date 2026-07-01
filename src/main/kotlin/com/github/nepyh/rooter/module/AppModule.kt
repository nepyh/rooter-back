package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.common.database.DatabaseConfig
import com.github.nepyh.rooter.common.database.DatabaseManager
import com.github.nepyh.rooter.module.example.ExampleModule
import com.github.nepyh.rooter.module.health.HealthModule
import com.github.nepyh.rooter.module.storage.FileStorageModule
import com.github.nepyh.rooter.module.swagger.SwaggerDocsModule
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.inject


fun AppModule(appConfig: AppConfig): Module = module {
    if (appConfig.environment == EnvironmentMode.DEV) {
        includes(
            ExampleModule(),
            SwaggerDocsModule()
        )
    }

    includes(
        HealthModule(),
        FileStorageModule(appConfig)
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
}
