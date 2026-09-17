package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.CatalogService
import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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

    authenticate("auth-jwt") {
        get("/recommended-textbooks") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val recommended = catalogService.getRecommendedTextbooks(userId)
                call.respond(HttpStatusCode.OK, recommended)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Catalog")
            summary = "내 학교/학년 기준 추천 교과서 목록 조회"
            description = "student_profiles 의 school_id/grade 로 school_textbook_adoptions 를 조회. " +
                "학생 프로필이 없거나 해당 학교/학년에 매핑 데이터가 없는 과목은 결과에서 그냥 빠짐 (에러 아님) — " +
                "빈 목록이거나 일부 과목이 누락됐으면 프론트는 기존 교과서 직접 선택 플로우로 폴백해야 함"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공 (빈 목록일 수 있음)"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<RecommendedTextbookResponse>>()
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
    }
}