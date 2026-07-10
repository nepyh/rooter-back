package com.github.nepyh.rooter.module.health

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.core.qualifier.named
import org.koin.dsl.module


@OptIn(ExperimentalKtorApi::class)
fun HealthModule() = module {
    single(named("healthApi")) {
        ApiRoute {
            get("health") {
                call.respondResource("banana.png")
            }.describe {
                tag("Health")
                summary = "헬스 체크"
                description = "서버 상태 확인"
                responses {
                    HttpStatusCode.OK {
                        description = "서버 정상"
                    }
                }
            }
        }
    }
}
