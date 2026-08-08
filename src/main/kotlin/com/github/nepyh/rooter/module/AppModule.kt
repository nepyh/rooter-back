package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.example.ExampleModule
import com.github.nepyh.rooter.module.health.HealthModule
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerModule
import com.github.nepyh.rooter.module.storage.FileStorageModule
import com.github.nepyh.rooter.module.swagger.SwaggerDocsModule
import com.github.nepyh.rooter.module.user.UserModule
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.inject

fun AppModule(appConfig: AppConfig): Module = module {
    // dev-related modules
    if (appConfig.environment == EnvironmentMode.DEV) {
        includes(
            ExampleModule(),
            SwaggerDocsModule()
        )
    }
    // infra-related modules
    includes(
        HealthModule(),
        FileStorageModule(appConfig),
        SchedulerModule()
    )
    // service-related modules
    includes(
        UserModule(appConfig)
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

    // scheduler 엔진 시작 (애플리케이션 라이프사이클과 함께 종료됨)
    val schedulerEngine: SchedulerEngine by inject()
    schedulerEngine.start(this)
}
