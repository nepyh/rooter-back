package com.github.nepyh.rooter.module.blink

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.dsl.module

val blinkModule = module {
    single { BlinkService() }
}

fun Application.installBlinkModule() {
    routing {
        route("/blink") {
            configureBlinkAPI()
        }
    }
}
