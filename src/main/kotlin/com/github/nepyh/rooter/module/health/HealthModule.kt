package com.github.nepyh.rooter.module.health

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.dsl.module


val healthModule = module {
    single {
        ApiRoute {
            route("/health") {
                get("") {
                    call.respondResource("banana.png")
                }
            }
        }
    }
}
