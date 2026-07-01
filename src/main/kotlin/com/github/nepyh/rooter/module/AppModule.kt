package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.config.AppConfig
import com.github.nepyh.rooter.module.database.DatabaseConfig
import com.github.nepyh.rooter.module.database.DatabaseManager
import com.github.nepyh.rooter.module.example.exampleModule
import com.github.nepyh.rooter.module.health.healthModule
import com.github.nepyh.rooter.module.storage.fileStorageModule
import com.github.nepyh.rooter.module.swagger.swaggerModule
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.inject


fun AppModule(appConfig: AppConfig): Module = module {
    single { appConfig }

    includes(exampleModule)
    includes(swaggerModule)
    includes(healthModule)

    includes(fileStorageModule)

    single<List<ApiRoute>> { getAll() }
}

fun Application.configureAppModule() {
    val appConfig: AppConfig by inject()

    DatabaseManager.init(
        DatabaseConfig(
            driverClassName = "org.postgresql.Driver",
            jdbcUrl = appConfig.jdbcUrl,
            username = appConfig.dbUsername,
            password = appConfig.dbPassword,
            maxPoolSize = appConfig.dbMaxPoolSize
        )
    )

    val apiRoutes: List<ApiRoute> by inject()

    routing {
        route("api") {
            apiRoutes.forEach { apiRoute ->
                with(apiRoute) { configureRoute() }
            }
        }
    }
}
