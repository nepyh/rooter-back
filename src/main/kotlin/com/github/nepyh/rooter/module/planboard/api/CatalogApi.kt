package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.CatalogService
import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun CatalogApi(catalogService: CatalogService) = ApiRoute("catalog") {

    get("/subjects") {
        try {
            val subjects = catalogService.getAllSubjects()
            call.respond(HttpStatusCode.OK, subjects)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Catalog")
        summary = "과목 목록 조회"
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<List<SubjectResponse>>()
                }
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    get("/subjects/{subjectId}/textbooks") {
        try {
            val subjectId = call.parameters["subjectId"]?.toIntOrNull()
            if (subjectId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 subjectId 입니다."))
                return@get
            }
            val textbooks = catalogService.getTextbooksBySubject(subjectId)
            call.respond(HttpStatusCode.OK, textbooks)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Catalog")
        summary = "과목별 교과서 목록 조회"
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<List<TextbookResponse>>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "잘못된 subjectId"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    get("/textbooks/{textbookId}/chapters") {
        try {
            val textbookId = call.parameters["textbookId"]?.toIntOrNull()
            if (textbookId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 textbookId 입니다."))
                return@get
            }
            val chapters = catalogService.getChaptersByTextbook(textbookId)
            call.respond(HttpStatusCode.OK, chapters)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Catalog")
        summary = "교과서별 단원 목록 조회"
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<List<ChapterResponse>>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "잘못된 textbookId"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    get("/textbooks/{textbookId}/detail") {
        try {
            val textbookId = call.parameters["textbookId"]?.toIntOrNull()
            if (textbookId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 textbookId 입니다."))
                return@get
            }
            val detail = catalogService.getTextbookDetail(textbookId)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "교과서를 찾을 수 없습니다."))
                return@get
            }
            call.respond(HttpStatusCode.OK, detail)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Catalog")
        summary = "교과서 상세 조회 (목차 트리 포함)"
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<TextbookDetailResponse>()
                }
            }
            HttpStatusCode.NotFound {
                description = "교과서 없음"
            }
            HttpStatusCode.BadRequest {
                description = "잘못된 textbookId"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
}