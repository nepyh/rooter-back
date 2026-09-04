package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.ChangePasswordRequest
import com.github.nepyh.rooter.module.user.dto.PasswordUpdateResponse
import com.github.nepyh.rooter.module.user.dto.StreakResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.user.dto.UpdateProfileRequest
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserProfileUpdateResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.http.content.file
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.flow.fold
import java.time.LocalDate


@OptIn(ExperimentalKtorApi::class)
fun UserApi(userService: UserService) = ApiRoute("users") {
    post("") {
        val request = call.receive<UserRegisterRequest>()
        val response = userService.registerUser(request)
        call.respond(HttpStatusCode.Created, response)
    }.describe {
        tag("User")
        summary = "회원가입"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<UserRegisterRequest>()
            }
        }
        responses {
            HttpStatusCode.Created {
                description = "회원가입 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<UserRegisterResponse>()
                }
            }
            HttpStatusCode.BadRequest {
                description = "사용할 수 없는 사용자 이름 (12자 초과, code=INVALID_USERNAME), 비밀번호 형식 오류 (code=INVALID_PASSWORD_FORMAT), 또는 이메일 320자 초과 (code=EMAIL_TOO_LONG)"
            }
            HttpStatusCode.Conflict {
                description = "이미 사용 중인 이메일 (code=DUPLICATED_EMAIL)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    authenticate("auth-jwt") {
        get("{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 조회할 수 있습니다."))
            }
            val response = userService.getUserInfo(id)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "유저 정보 조회"
            description = "유저 기본 정보와 학생 프로필을 함께 조회. 본인 정보만 조회 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<UserInfoResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{id}/streak") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 조회할 수 있습니다."))
            }

            val startParam = call.request.queryParameters["start"]
            val endParam = call.request.queryParameters["end"]
            if (startParam == null || endParam == null) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_RANGE_PARAM", "start, end 파라미터가 필요합니다."))
            }
            val start = runCatching { LocalDate.parse(startParam) }
                .getOrElse { throw UserValidationException.WrongDateFormatException() }
            val end = runCatching { LocalDate.parse(endParam) }
                .getOrElse { throw UserValidationException.WrongDateFormatException() }

            val response = userService.getStreak(id, start, end)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "잔디 심기 (기간별 학습 완료율)"
            description = "start~end 범위의 날짜별 태스크 완료율을 반환. 본인 정보만 조회 가능. 마이페이지 잔디 그리드용"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                query("start") {
                    description = "조회 시작일 (yyyy-MM-dd)"
                    required = true
                    schema = jsonSchema<String>()
                }
                query("end") {
                    description = "조회 종료일 (yyyy-MM-dd)"
                    required = true
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<StreakResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, start/end 누락 (code=MISSING_RANGE_PARAM), 날짜 형식 오류 (code=INVALID_DATE_FORMAT), 또는 start 가 end 보다 늦음 (code=INVALID_DATE_RANGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        patch("{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 수정할 수 있습니다."))
            }
            val request = call.receive<UpdateProfileRequest>()
            val response = userService.updateProfile(id, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "이름/소개 수정"
            description = "username, bio 중 전달된 필드만 수정. 본인 정보만 수정 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<UpdateProfileRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "수정 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<UserProfileUpdateResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, username 12자 초과 (code=INVALID_USERNAME), 또는 bio 500자 초과 (code=BIO_TOO_LONG)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        put("{id}/password") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 수정할 수 있습니다."))
            }
            val request = call.receive<ChangePasswordRequest>()
            val response = userService.changePassword(id, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "비밀번호 변경"
            description = "현재 비밀번호 확인 후 새 비밀번호로 변경. 본인 정보만 변경 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ChangePasswordRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "변경 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PasswordUpdateResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 새 비밀번호 형식이 올바르지 않음 (code=INVALID_PASSWORD_FORMAT)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음, 또는 현재 비밀번호 불일치 (code=WRONG_CURRENT_PASSWORD)"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        put("{id}/avatar") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 수정할 수 있습니다."))
            }

            val fileItem: PartData.FileItem = call
                .receiveMultipart()
                .asFlow()
                .fold(null as PartData.FileItem?) { acc, part ->
                    when {
                        acc != null -> { part.dispose(); acc }
                        part is PartData.FileItem -> part
                        else -> { part.dispose(); null }
                    }
            } ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("IMAGE_REQUIRED", "이미지 파일이 필요합니다."))

            val response = userService.updateAvatar(id, fileItem)
            fileItem.dispose()
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "아바타 이미지 업로드"
            description = "이미지 파일을 업로드하고 유저의 avatarImageKey 를 갱신. 본인 정보만 수정 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.MultiPart.FormData {}
            }
            responses {
                HttpStatusCode.OK {
                    description = "업로드 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<AvatarUpdateResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 이미지 파일 누락"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{id}/unavailable-times") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 조회할 수 있습니다."))
            }
            val response = userService.getUnavailableTimes(id)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "불가능 시간 목록 조회"
            description = "본인 정보만 조회 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<UnavailableTimeResponse>>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{id}/unavailable-times") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 등록할 수 있습니다."))
            }
            val request = call.receive<UnavailableTimeRequest>()
            val response = userService.addUnavailableTime(id, request)
            call.respond(HttpStatusCode.Created, response)
        }.describe {
            tag("User")
            summary = "불가능 시간 추가"
            description = "요일(1~7)과 시작/종료 시간을 등록. 본인 정보만 등록 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<UnavailableTimeRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "등록 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<UnavailableTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 dayOfWeek 가 1~7 범위 밖 (code=INVALID_DAY_OF_WEEK)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{id}/profile") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 등록할 수 있습니다."))
            }
            val request = call.receive<StudentProfileRequest>()
            val response = userService.createStudentProfile(id, request)
            call.respond(HttpStatusCode.Created, response)
        }.describe {
            tag("User")
            summary = "학생 프로필 생성"
            description = "본인 정보만 등록 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<StudentProfileRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<StudentProfileResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID, 또는 schoolId 가 10자 초과 (code=INVALID_SCHOOL_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
