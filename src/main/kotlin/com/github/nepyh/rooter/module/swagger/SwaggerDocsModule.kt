package com.github.nepyh.rooter.module.swagger

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.core.qualifier.named
import org.koin.dsl.module


@OptIn(ExperimentalKtorApi::class)
fun SwaggerDocsModule() = module {
    single(named("swaggerApi")) {
        ApiRoute {
            swaggerUI(path = "swagger") {
                info = OpenApiInfo(
                    title = "Rooter API",
                    version = "1.0.0",
                    description = "Rooter 백엔드 API 문서"
                )
                // Swagger UI 는 첫 번째 서버를 기본값으로 쓰므로, 배포된 Swagger 에서 바로 Try it out 할 수 있게 AWS 를 맨 앞에 둔다
                servers {
                    server("https://ro-897fe1b87ce043b3968caf53c5ef0699.ecs.ap-northeast-2.on.aws") {
                        description = "AWS 배포 서버"
                    }
                    server("http://localhost:8080") {
                        description = "로컬 개발 서버"
                    }
                    server("https://anyone-enhance-lustrous.ngrok-free.dev") {
                        description = "ngrok 배포 서버"
                    }
                }
                source = OpenApiDocSource.Routing()
            }
        }
    }
}
