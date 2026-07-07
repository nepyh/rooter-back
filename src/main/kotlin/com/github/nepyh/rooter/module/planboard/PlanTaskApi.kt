package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

class PlanTaskApi(private val planTaskService: PlanTaskService) {
    fun Route.registerRoutes() {
        route("plan-tasks") {

            get {
                call.respondCatching {
                    val dateParam = call.request.queryParameters["date"]
                    val date = if (dateParam != null) {
                        runCatching { LocalDate.parse(dateParam) }
                            .getOrElse { throw ApiException(ErrorCode.TASK_006) }
                    } else {
                        LocalDate.now()
                    }

                    val userId = 1 // 💡 로그인 연동 전 임시 유저
                    val dailyPlan = planTaskService.getDailyPlan(userId, date)
                    call.respond(HttpStatusCode.OK, dailyPlan)
                }
            }

            post {
                call.respondCatching {
                    val request = call.receive<PlanTaskCreateRequest>()
                    planTaskService.createTask(request)
                    call.respond(HttpStatusCode.Created, mapOf("message" to "성공적으로 등록되었습니다."))
                }
            }
        }
    }
}