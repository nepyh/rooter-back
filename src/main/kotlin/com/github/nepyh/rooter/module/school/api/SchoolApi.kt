package com.github.nepyh.rooter.module.school.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.school.dto.SchoolSearchResponse
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

private const val MAX_RESULTS = 20

@OptIn(ExperimentalKtorApi::class)
fun SchoolApi(schoolDataFetcher: SchoolDataFetcher) = ApiRoute("school") {
    get("search") {
        val name = call.request.queryParameters["name"]
        if (name.isNullOrBlank()) {
            throw NiceApiException.InvalidSearchQueryException()
        }

        val schools = schoolDataFetcher.searchSchools(name, limit = MAX_RESULTS)
        call.respond(
            HttpStatusCode.OK,
            schools.map {
                SchoolSearchResponse(
                    schoolId = it.schoolId,
                    name = it.name,
                    officeName = it.officeName,
                    region = it.region,
                    foundation = it.foundation
                )
            }
        )
    }.describe {
        tag("School")
        summary = "학교 검색 (회원가입 자동완성용)"
        description = "이름 부분 일치로 중학교를 검색. 인증 불필요(회원가입 전에도 호출 가능). " +
            "응답의 schoolId 를 student_profiles 생성 시 그대로 사용"
        parameters {
            query("name") {
                description = "검색할 학교명 (부분 일치)"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "조회 성공 (결과 없으면 빈 배열)"
                ContentType.Application.Json {
                    schema = jsonSchema<List<SchoolSearchResponse>>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "검색어가 비어있음 (code=NICE_INVALID_SEARCH_QUERY)"
            }
            HttpStatusCode.TooManyRequests {
                description = "NICE API 호출 한도 초과 (code=NICE_RATE_LIMITED)"
            }
            HttpStatusCode.BadGateway {
                description = "NICE API 오류 (code=NICE_SERVER_ERROR / NICE_UNEXPECTED_RESPONSE)"
            }
        }
    }
}
