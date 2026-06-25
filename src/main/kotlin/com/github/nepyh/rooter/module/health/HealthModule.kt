package com.github.nepyh.rooter.module.health

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.qualifier.named
import org.koin.dsl.module


fun HealthModule() = module {
    single(named("healthApi")) {
        ApiRoute {
            get("health") {
                call.respondResource("banana.png")
            }
        }
    }
}
