package com.github.nepyh.ebazi.module.blink

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.configureBlinkAPI() {
    get("") {
        call.respondText("Hello Ebazi!")
    }
}