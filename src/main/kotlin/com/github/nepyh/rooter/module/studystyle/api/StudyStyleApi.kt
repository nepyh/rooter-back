package com.github.nepyh.rooter.module.studystyle.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.studystyle.BestTime
import com.github.nepyh.rooter.module.studystyle.DailyStudyHabit
import com.github.nepyh.rooter.module.studystyle.Distraction
import com.github.nepyh.rooter.module.studystyle.FocusSpan
import com.github.nepyh.rooter.module.studystyle.LearningMethod
import com.github.nepyh.rooter.module.studystyle.StudyStyleService
import com.github.nepyh.rooter.module.studystyle.UnfinishedHabit
import com.github.nepyh.rooter.module.studystyle.VolatileTime
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleResponse
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleSubmitRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun StudyStyleApi(studyStyleService: StudyStyleService) = ApiRoute("study-style") {
    authenticate("auth-jwt") {
        post("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<StudyStyleSubmitRequest>()

            val response = studyStyleService.submitAnswers(userId, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("StudyStyle")
            summary = "공부스타일 설문 제출"
            description = "questionNumber(1~7)별 answerOption(1~3, 4=모르겠어요)을 제출. 제출한 문항만 기존 답변을 덮어씀, 전체 재제출 불필요.\n\n" +
                "AI 계획 생성(할일 길이·내용·tips)과 할일 배치 시각, 챗봇에 반영되므로 **문항·보기 순서는 아래와 같이 고정**이다 (바꾸면 엉뚱하게 해석됨).\n\n" +
                STUDY_STYLE_SURVEY_GUIDE
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<StudyStyleSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "제출 성공, 현재까지 저장된 전체 답변 반환"
                    ContentType.Application.Json {
                        schema = jsonSchema<StudyStyleResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "answers가 비어있음 (code=STUDY_STYLE_EMPTY_ANSWERS), questionNumber 범위 오류 (code=STUDY_STYLE_INVALID_QUESTION_NUMBER), answerOption 범위 오류 (code=STUDY_STYLE_INVALID_ANSWER_OPTION), 또는 같은 문항 중복 제출 (code=STUDY_STYLE_DUPLICATE_QUESTION_NUMBER)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val response = studyStyleService.getAnswers(userId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("StudyStyle")
            summary = "공부스타일 설문 응답 조회"
            description = "본인이 제출한 답변만 조회 (미응답 문항은 목록에서 생략됨)"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<StudyStyleResponse>()
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

// 문항·보기 의미를 해석 로직(StudyStyleProfile)의 enum 에서 그대로 만들어, 문서와 해석이 어긋나지 않게 한다
private val STUDY_STYLE_SURVEY_GUIDE: String = listOf(
    "한 번에 최대로 집중할 수 있는 시간" to FocusSpan.entries.map { it.label },
    "하루 중 공부가 가장 잘 되는 시간대" to BestTime.entries.map { it.label },
    "새로운 내용을 공부할 때 선호하는 방식" to LearningMethod.entries.map { it.label },
    "하루 공부 스케줄에서 가장 변수가 많은 시간대" to VolatileTime.entries.map { it.label },
    "시험 기간이 아닐 때 평소 하루 자습 시간" to DailyStudyHabit.entries.map { it.label },
    "계획한 공부를 다 못 끝냈을 때" to UnfinishedHabit.entries.map { it.label },
    "공부할 때 가장 크게 방해가 되는 요소" to Distraction.entries.map { it.label }
).mapIndexed { index, (question, options) ->
    "${index + 1}. $question — " + (options + "모르겠어요").mapIndexed { i, option -> "${i + 1}) $option" }.joinToString(" / ")
}.joinToString("\n\n") // Swagger 마크다운에서 줄마다 끊기게 빈 줄로 나눈다
