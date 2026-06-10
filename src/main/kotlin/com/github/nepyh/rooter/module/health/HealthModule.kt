package com.github.nepyh.rooter.module.health

import io.ktor.server.application.*
import io.ktor.server.response.respondResource
import io.ktor.server.routing.*

fun Application.installHealthModule() {
    routing {
        route("/health") {
            get("") {
                call.respondResource("banana.png")
            }
        }
    }
}
