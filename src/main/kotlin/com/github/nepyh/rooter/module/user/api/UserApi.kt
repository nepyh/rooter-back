package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
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


@OptIn(ExperimentalKtorApi::class)
fun UserApi(userService: UserService) = ApiRoute("users") {
    post("") {
        try {
            val request = call.receive<UserRegisterRequest>()
            val response = userService.registerUser(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: UserValidationException.WrongUsernameException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))           // 400
        } catch (e: UserValidationException.DuplicatedEmailException) {
            call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))              // 409
        } catch (e: UserValidationException.WrongUsernameAlreadyException) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to e.message))    // 422
        } catch (e: UserValidationException.WrongPasswordFormatException) {
            call.respond(HttpStatusCode.NotAcceptable, mapOf("message" to e.message))          // 406
        } catch (e: UserValidationException.WrongEmailLengthException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "EMAIL_TOO_LONG", "message" to e.message)) // 400
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))  // 500
        }
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
                description = "사용할 수 없는 사용자 이름 (12자 초과)"
            }
            HttpStatusCode.Conflict {
                description = "이미 사용 중인 이메일"
            }
            HttpStatusCode.UnprocessableEntity {
                description = "이미 있는 사용자 이름"
            }
            HttpStatusCode.NotAcceptable {
                description = "비밀번호 형식이 올바르지 않음"
            }
            HttpStatusCode.PayloadTooLarge {
                description = "이메일이 320자를 초과함 (code=EMAIL_TOO_LONG)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    authenticate("auth-jwt") {
        get("{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                if (principalUserId != id) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("message" to "본인 정보만 조회할 수 있습니다."))
                }
                val response = userService.getUserInfo(id)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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

        put("{id}/avatar") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                if (principalUserId != id) {
                    return@put call.respond(HttpStatusCode.Forbidden, mapOf("message" to "본인 정보만 수정할 수 있습니다."))
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
                } ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "이미지 파일이 필요합니다."))

                val response = userService.updateAvatar(id, fileItem)
                fileItem.dispose()
                call.respond(HttpStatusCode.OK, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                if (principalUserId != id) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("message" to "본인 정보만 조회할 수 있습니다."))
                }
                val response = userService.getUnavailableTimes(id)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                if (principalUserId != id) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("message" to "본인 정보만 등록할 수 있습니다."))
                }
                val request = call.receive<UnavailableTimeRequest>()
                val response = userService.addUnavailableTime(id, request)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (_: Exception) { // TODO ㅣㅇ거 json 파싱 오류도 그냥 500으로 처박힘
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                if (principalUserId != id) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("message" to "본인 정보만 등록할 수 있습니다."))
                }
                val request = call.receive<StudentProfileRequest>()
                val response = userService.createStudentProfile(id, request)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: UserValidationException.WrongSchoolIdException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "INVALID_SCHOOL_ID", "message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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
