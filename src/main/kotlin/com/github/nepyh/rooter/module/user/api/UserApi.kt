package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.AgreementsUpdateRequest
import com.github.nepyh.rooter.module.user.dto.AgreementsUpdateResponse
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.ChangePasswordRequest
import com.github.nepyh.rooter.module.user.dto.PasswordUpdateResponse
import com.github.nepyh.rooter.module.user.dto.CommuteTimeRequest
import com.github.nepyh.rooter.module.user.dto.CommuteTimeResponse
import com.github.nepyh.rooter.module.user.dto.SleepTimeRequest
import com.github.nepyh.rooter.module.user.dto.SleepTimeResponse
import com.github.nepyh.rooter.module.user.dto.StreakResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.user.dto.UpdateProfileRequest
import com.github.nepyh.rooter.module.user.dto.UserAgreementsResponse
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
import java.time.LocalDate


@OptIn(ExperimentalKtorApi::class)
fun UserApi(userService: UserService, authService: AuthService) = ApiRoute("users") {
    post("") {
        val request = call.receive<UserRegisterRequest>()
        val response = userService.registerUser(request)
        call.respond(HttpStatusCode.Created, response)
    }.describe {
        tag("User")
        summary = "회원가입"
        description = "agreements 는 선택 필드다(구버전 앱은 안 보냄). 보내면 필수 항목이 모두 agreed=true 이고 " +
            "version 이 현재 버전과 같아야 201 이 나가고, 유저 생성과 같은 트랜잭션에서 이력이 저장된다. " +
            "안 보내면 가입은 되고 로그인 응답의 pendingAgreements 로 다시 동의를 받는다."
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
                description = "사용할 수 없는 사용자 이름 (12자 초과, code=INVALID_USERNAME), 비밀번호 형식 오류 (code=INVALID_PASSWORD_FORMAT), 또는 이메일 320자 초과 (code=EMAIL_TOO_LONG), 또는 agreements 검증 실패 " +
                        "(code=UNKNOWN_AGREEMENT_CODE / DUPLICATED_AGREEMENT_CODE / AGREEMENT_VERSION_MISMATCH / REQUIRED_AGREEMENT_MISSING / EMPTY_AGREEMENTS)"
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
        post("{id}/agreements") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 동의만 변경할 수 있습니다."))
            }
            val request = call.receive<AgreementsUpdateRequest>()
            call.respond(HttpStatusCode.OK, userService.updateAgreements(id, request))
        }.describe {
            tag("User")
            summary = "약관 재동의·철회"
            description = "약관이 개정돼 pendingAgreements 로 안내된 뒤 앱이 호출한다. 보낸 항목만 검증해 새 행으로 남기고 " +
                "(이력 append-only) 남은 필수 미동의 code 를 돌려준다. 필수 항목도 agreed=false 로 보내 철회할 수 있고, " +
                "철회한 필수 항목은 응답과 이후 로그인의 pendingAgreements 에 다시 나온다. " +
                "본인 동의만 변경 가능."
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<AgreementsUpdateRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "저장 성공. pendingAgreements 가 비어 있으면 동의 화면을 닫아도 된다"
                    ContentType.Application.Json {
                        schema = jsonSchema<AgreementsUpdateResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 또는 agreements 검증 실패 " +
                        "(code=UNKNOWN_AGREEMENT_CODE / DUPLICATED_AGREEMENT_CODE / AGREEMENT_VERSION_MISMATCH / EMPTY_AGREEMENTS)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 동의가 아님 (code=FORBIDDEN)"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저 (code=USER_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{id}/agreements") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 동의만 조회할 수 있습니다."))
            }
            call.respond(HttpStatusCode.OK, userService.getAgreements(id))
        }.describe {
            tag("User")
            summary = "내 동의 이력 조회"
            description = "동의 항목별 가장 최근 1건만 준다. 한 번도 동의하지 않은 항목은 응답에서 빠진다. 본인 것만 조회 가능."
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
                        schema = jsonSchema<UserAgreementsResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 동의가 아님 (code=FORBIDDEN)"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 유저 (code=USER_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

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
            description = "유저 기본 정보와 학생 프로필을 함께 조회. 본인 정보만 조회 가능. 학생 프로필 등록 전이면 schoolId·grade·classNumber 는 null" +
                "avatarUrl 은 아바타를 등록한 적이 없으면 null (S3 사용 시 presigned URL 이라 일정 시간 후 만료되므로 매번 새로 조회해야 함)"
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

        delete("{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 계정만 탈퇴할 수 있습니다."))
            }
            userService.deleteUser(id)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            tag("User")
            summary = "회원 탈퇴"
            description = "본인 계정을 삭제한다. 프로필·학교 정보·불가능 시간·플랜보드·할일·퀴즈·복습·캘린더 등 이 유저의 기록이 모두 함께 지워지고 되돌릴 수 없다. " +
                "아바타 이미지도 스토리지에서 지운다. 탈퇴 후에는 기존 토큰이 401 로 막히므로 앱은 저장된 토큰을 버리고 로그인 화면으로 보내야 한다. " +
                "같은 이메일로 다시 가입하면 새 계정이 된다"
            parameters {
                path("id") {
                    description = "유저 ID (본인)"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.NoContent {
                    description = "탈퇴 성공"
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 계정이 아님 (code=FORBIDDEN)"
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
            val response = authService.changePassword(id, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "비밀번호 변경"
            description = "현재 비밀번호 확인 후 새 비밀번호로 변경. 본인 정보만 변경 가능. 변경하면 기존에 발급된 토큰은 모두 무효화되고(다른 기기 로그아웃), 응답의 token 으로 교체해서 써야 함. " +
                "등록된 푸시 토큰도 전부 지워지므로(잃어버린 기기에 알림이 가지 않게), 앱은 새 token 을 저장한 직후 POST /notifications/device-tokens 로 이 기기의 푸시 토큰을 다시 등록해야 함"
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

            // PartData.FileItem 은 스트리밍 채널이라 파트를 받은 자리에서 즉시 읽어야 함.
            // forEachPart 가 각 파트를 넘겨준 block 이 끝나야 다음 파트로 넘어가면서 dispose 하므로,
            // block 안에서 곧바로 업로드를 끝내야 채널이 소진되기 전에 데이터를 읽을 수 있다.
            var response: AvatarUpdateResponse? = null
            call.receiveMultipart().forEachPart { part ->
                if (response == null && part is PartData.FileItem) {
                    response = userService.updateAvatar(id, part)
                }
            }

            response?.let { call.respond(HttpStatusCode.OK, it) }
                ?: call.respond(HttpStatusCode.BadRequest, ErrorResponse("IMAGE_REQUIRED", "이미지 파일이 필요합니다."))
        }.describe {
            tag("User")
            summary = "아바타 이미지 업로드"
            description = "이미지 파일을 아바타로 등록하고 응답의 avatarUrl 로 바로 표시할 수 있음 " +
                "(S3 사용 시 presigned URL 이라 일정 시간 후 만료되므로, 다시 보려면 유저 정보 조회로 새로 받아야 함). " +
                "본인 정보만 수정 가능. 허용 확장자: jpg, jpeg, png, webp / 최대 용량: 5MB"
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
                    description = "유효하지 않은 ID, 이미지 파일 누락(code=IMAGE_REQUIRED), " +
                        "허용되지 않는 확장자(code=UNSUPPORTED_AVATAR_FILE_TYPE, jpg/jpeg/png/webp만 허용), " +
                        "또는 5MB 초과(code=AVATAR_FILE_TOO_LARGE)"
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
            description = "요일(1~7)과 시작/종료 시간을 등록. kind 로 종류를 고름: BREAKFAST·LUNCH·DINNER(식사) / ACADEMY(학원) / CUSTOM(기타, 생략 시 기본). " +
                "label 은 사용자가 붙인 이름(50자 이하, 생략 가능). " +
                "식사는 그 요일에 등록하면 등록한 시간을, 등록하지 않으면 기본값(아침 기상 후 30분, 점심 12:00~13:00, 저녁 18:00~19:00)을 막음. 본인 정보만 등록 가능"
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
                    description = "유효하지 않은 ID, dayOfWeek 가 1~7 범위 밖 (code=INVALID_DAY_OF_WEEK), label 이 50자 초과 (code=INVALID_UNAVAILABLE_LABEL), " +
                        "또는 kind 가 정해진 값이 아님 (code=INVALID_REQUEST_BODY)"
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

        delete("{id}/unavailable-times/{timeId}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val timeId = call.parameters["timeId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 삭제할 수 있습니다."))
            }
            userService.deleteUnavailableTime(id, timeId)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            tag("User")
            summary = "불가능 시간 삭제"
            description = "본인 정보만 삭제 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                path("timeId") {
                    description = "삭제할 불가능 시간 ID (등록/조회 응답의 id)"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
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
                    description = "본인 정보가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 불가능 시간"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{id}/sleep-time") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 조회할 수 있습니다."))
            }
            val response = userService.getSleepTime(id)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "수면시간 조회"
            description = "저장한 취침·기상 시각(HH:mm). 아직 저장하지 않았으면 기본값 23:00 / 06:30 과 isDefault=true. 본인 정보만 조회 가능"
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
                        schema = jsonSchema<SleepTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID)"
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

        put("{id}/sleep-time") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 수정할 수 있습니다."))
            }
            val request = call.receive<SleepTimeRequest>()
            val response = userService.updateSleepTime(id, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "수면시간 저장"
            description = "취침·기상 시각(HH:mm)을 저장. 이후 AI 계획 생성·바쁜 시간 조회(SLEEP)·챗봇 재조정/미루기·복습 배치가 이 시간을 피해서 할일을 잡음. " +
                "자정 전에 자면(23:30 취침, 07:00 기상) 00:00~07:00 과 23:30~24:00 을, 자정 넘어 자면(01:00 취침, 07:00 기상) 01:00~07:00 을 막음. " +
                "이미 잡혀 있는 할일은 옮기지 않음. 본인 정보만 저장 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<SleepTimeRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "저장 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<SleepTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 시각 형식 오류 (code=INVALID_TIME_FORMAT), 또는 취침·기상 시각이 같음 (code=INVALID_SLEEP_TIME)"
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

        get("{id}/commute-time") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 조회할 수 있습니다."))
            }
            val response = userService.getCommuteTime(id)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "귀가 시간 조회"
            description = "하교 후 집에 도착하기까지 걸리는 시간(분). 아직 저장하지 않았으면 기본값 40 과 isDefault=true. 본인 정보만 조회 가능"
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
                        schema = jsonSchema<CommuteTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID)"
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

        put("{id}/commute-time") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val principalUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            if (principalUserId != id) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "본인 정보만 수정할 수 있습니다."))
            }
            val request = call.receive<CommuteTimeRequest>()
            val response = userService.updateCommuteTime(id, request)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("User")
            summary = "귀가 시간 저장"
            description = "하교 후 집에 도착하기까지 걸리는 시간(분, 0~180)을 저장. 이후 AI 계획 생성·바쁜 시간 조회(COMMUTE)·챗봇 재조정/미루기·복습 배치가 " +
                "등교일의 하교 시각부터 이 시간만큼은 할일을 잡지 않음. 0 이면 하교하자마자 할일을 잡을 수 있음. 이미 잡혀 있는 할일은 옮기지 않음. 본인 정보만 저장 가능"
            parameters {
                path("id") {
                    description = "유저 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<CommuteTimeRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "저장 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<CommuteTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 또는 0~180 범위 밖 (code=INVALID_COMMUTE_MINUTES)"
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
