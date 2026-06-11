package com.github.nepyh.rooter.module.example

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.response.*
import io.ktor.server.routing.*


fun ExampleApi(service: ExampleService) = ApiRoute("/example") {
    get("") {
        call.respondText("이건 예제 API 임")
    }
    get("/random") {
        call.respondText(service.getRandomNumber().toString())
    }
}
