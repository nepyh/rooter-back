package com.github.nepyh.rooter.module.school.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.school.dto.SchoolExamScheduleResponse
import com.github.nepyh.rooter.module.school.dto.SchoolSearchResponse
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

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

    get("exam-schedule") {
        val schoolId = call.request.queryParameters["schoolId"]
        if (schoolId.isNullOrBlank()) {
            throw NiceApiException.InvalidSchoolIdException()
        }
        val year = call.request.queryParameters["year"]?.toIntOrNull() ?: LocalDate.now().year

        val candidates = schoolDataFetcher.getExamScheduleCandidates(schoolId, year)
        call.respond(
            HttpStatusCode.OK,
            candidates.map { SchoolExamScheduleResponse(date = it.date.toString(), name = it.name) }
        )
    }.describe {
        tag("School")
        summary = "학사일정 기반 시험기간 후보 조회"
        description = "NICE 학사일정(SchoolSchedule)에서 이벤트명에 '고사'/'시험'이 포함된 항목만 추려서 반환. " +
            "정확한 시험 분류가 아니라 examDate 입력을 돕는 추천 후보 — 최종 확정은 사용자가 함. 인증 불필요"
        parameters {
            query("schoolId") {
                description = "학교 합성 식별자 (school/search 응답의 schoolId)"
                required = true
                schema = jsonSchema<String>()
            }
            query("year") {
                description = "학년도(AY). 생략하면 올해"
                required = false
                schema = jsonSchema<Int>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "조회 성공 (결과 없으면 빈 배열)"
                ContentType.Application.Json {
                    schema = jsonSchema<List<SchoolExamScheduleResponse>>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "schoolId 누락 (code=NICE_INVALID_SCHOOL_ID)"
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
