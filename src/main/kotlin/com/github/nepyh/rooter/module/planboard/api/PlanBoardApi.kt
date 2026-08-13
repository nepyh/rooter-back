package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanBoardService
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun PlanBoardApi(planBoardService: PlanBoardService) = ApiRoute("plan-boards") {
    get("") {
        val boards = planBoardService.getAllBoards()
        call.respond(HttpStatusCode.OK, boards)
    }.describe {
        tag("PlanBoard")
        summary = "플랜보드 목록 조회"
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<List<PlanBoardResponse>>()
                }
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    post("") {
        val request = call.receive<PlanBoardCreateRequest>()
        val boardId = planBoardService.createBoard(request)
        call.respond(HttpStatusCode.Created, PlanBoardCreateResponse(boardId, "성공적으로 등록되었습니다."))
    }.describe {
        tag("PlanBoard")
        summary = "플랜보드 생성"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<PlanBoardCreateRequest>()
            }
        }
        responses {
            HttpStatusCode.Created {
                description = "생성 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<PlanBoardCreateResponse>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "제목이 1~100자를 벗어남 (code=BOARD_001), 날짜 형식이 잘못됨 (code=BOARD_002), 또는 종료일이 시작일보다 빠름 (code=BOARD_003)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
}
