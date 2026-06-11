package com.github.nepyh.rooter.module.blink

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.response.*
import io.ktor.server.routing.*


fun BlinkApi() = ApiRoute {
    get("") {
        call.respondText("이건 예제 API 임")
    }
}
