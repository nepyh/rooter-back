package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class PlanBoardApi(private val planBoardService: PlanBoardService) {
    fun Route.registerRoutes() {
        route("plan-boards") {
            get {
                call.respondCatching {
                    val boards = planBoardService.getAllBoards()
                    call.respond(status = HttpStatusCode.OK, message = boards)
                }
            }

            post {
                call.respondCatching {
                    val request = call.receive<PlanBoardCreateRequest>()
                    planBoardService.createBoard(request)
                    call.respond(status = HttpStatusCode.Created, message = mapOf("message" to "성공적으로 등록되었습니다."))
                }
            }
        }
    }
}