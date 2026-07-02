package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class PlanBoardApi(private val planBoardService: PlanBoardService) {
    // 롤백: 원래 쓰던 대로 registerRoutes() 사용!
    fun Route.registerRoutes() {
        route("plan-boards") {
            get {
                val boards = planBoardService.getAllBoards()
                call.respond(status = HttpStatusCode.OK, message = boards)
            }

            post {
                val request = call.receive<PlanBoardCreateRequest>()
                planBoardService.createBoard(request)
                call.respond(status = HttpStatusCode.Created, message = mapOf("message" to "성공적으로 등록되었습니다."))
            }
        }
    }
}