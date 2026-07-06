package com.github.nepyh.rooter.module.example

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi


@OptIn(ExperimentalKtorApi::class)
fun ExampleApi(service: ExampleService) = ApiRoute("example") {
    get("") {
        call.respondText("이건 예제 API 임")
    }.describe {
        tag("Example")
        summary = "예제 API"
        description = "예제 텍스트 반환"
        responses {
            HttpStatusCode.OK {
                description = "예제 응답"
                ContentType.Text.Plain {
                    schema = jsonSchema<String>()
                }
            }
        }
    }
    get("random") {
        call.respondText(service.getRandomNumber().toString())
    }.describe {
        tag("Example")
        summary = "랜덤 숫자 반환"
        description = "랜덤 숫자를 반환"
        responses {
            HttpStatusCode.OK {
                description = "랜덤 숫자"
                ContentType.Text.Plain {
                    schema = jsonSchema<Int>()
                }
            }
        }
    }
}
