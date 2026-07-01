package com.github.nepyh.rooter.module.swagger

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.qualifier.named
import org.koin.dsl.module


val swaggerModule = module {
    single(named("swaggerApi")) {
        ApiRoute {
            swaggerUI(path = "swagger", swaggerFile = "openapi/docs.yaml")
        }
    }
}
