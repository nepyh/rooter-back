package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanBoardService
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun PlanBoardApi(planBoardService: PlanBoardService) = ApiRoute("plan-boards") {
    authenticate("auth-jwt") {
        get("") {
            val boards = planBoardService.getAllBoards(call.userId())
            call.respond(HttpStatusCode.OK, boards)
        }.describe {
            tag("PlanBoard")
            summary = "플랜보드 목록 조회"
            description = "본인 플랜보드 목록만 조회 가능"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<PlanBoardResponse>>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("") {
            val request = call.receive<PlanBoardCreateRequest>()
            val boardId = planBoardService.createBoard(call.userId(), request)
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
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "제목이 1~100자를 벗어남 (code=INVALID_TITLE), 날짜 형식이 잘못됨 (code=INVALID_DATE_FORMAT), 또는 종료일이 시작일보다 빠름 (code=INVALID_DATE_RANGE)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("{boardId}") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val request = call.receive<PlanBoardUpdateRequest>()
            val response = planBoardService.updateBoard(call.userId(), boardId, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("PlanBoard")
            summary = "플랜보드 수정"
            description = "title/startDate/endDate 중 전달된 필드만 수정. 본인 보드만 가능"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanBoardUpdateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "수정 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanBoardResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 제목이 1~100자를 벗어남 (code=INVALID_TITLE), 날짜 형식이 잘못됨 (code=INVALID_DATE_FORMAT), 또는 종료일이 시작일보다 빠름 (code=INVALID_DATE_RANGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("{boardId}") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            planBoardService.deleteBoard(call.userId(), boardId)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            tag("PlanBoard")
            summary = "플랜보드 삭제"
            description = "보드를 삭제하면 과목 범위/일별 계획/태스크 등 하위 데이터가 전부 함께 삭제됨. 본인 보드만 가능"
            responses {
                HttpStatusCode.NoContent {
                    description = "삭제 성공"
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{boardId}/subjects") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val request = call.receive<PlanSubjectCreateRequest>()
            val response = planBoardService.addSubject(call.userId(), boardId, request)
            call.respond(HttpStatusCode.Created, response)
        }.describe {
            tag("PlanBoard")
            summary = "과목 범위 등록 (AI 계획 생성 없이 수동으로)"
            description = "이 보드에서 어떤 교과서의 몇 단원부터 몇 단원까지 공부할지 등록. " +
                "AI 계획 생성(plan-generation)을 쓰지 않고 수동으로 보드를 관리할 때 사용. subjectId 는 textbookId 로부터 자동으로 구함"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanSubjectCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "등록 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanSubjectResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 존재하지 않는 교과서/단원이거나 시작 단원이 끝 단원보다 뒤에 있음 (code=INVALID_SUBJECT_RANGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{boardId}/subjects") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val response = planBoardService.getSubjects(call.userId(), boardId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("PlanBoard")
            summary = "과목 범위 목록 조회"
            description = "이 보드에 등록된 과목 범위 목록 조회. 본인 보드만 가능"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<PlanSubjectResponse>>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("{boardId}/subjects/{subjectId}") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val subjectId = call.parameters["subjectId"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val request = call.receive<PlanSubjectCreateRequest>()
            val response = planBoardService.updateSubject(call.userId(), boardId, subjectId, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("PlanBoard")
            summary = "과목 범위 수정"
            description = "textbookId/startChapterId/endChapterId/customRangeText 전체를 다시 받아 통째로 교체 (부분 수정 아님). 본인 보드만 가능"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanSubjectCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "수정 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanSubjectResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 존재하지 않는 교과서/단원이거나 시작 단원이 끝 단원보다 뒤에 있음 (code=INVALID_SUBJECT_RANGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드, 또는 존재하지 않거나 이 보드 소속이 아닌 과목 범위"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("{boardId}/subjects/{subjectId}") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val subjectId = call.parameters["subjectId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            planBoardService.deleteSubject(call.userId(), boardId, subjectId)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            tag("PlanBoard")
            summary = "과목 범위 삭제"
            description = "본인 보드만 가능"
            responses {
                HttpStatusCode.NoContent {
                    description = "삭제 성공"
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 보드, 또는 존재하지 않거나 이 보드 소속이 아닌 과목 범위"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
