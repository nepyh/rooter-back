package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginResponse
import com.github.nepyh.rooter.module.user.dto.UserLogoutResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi


private val loginBadCredentialsResponse = mapOf(
    "code" to "BAD_CREDENTIALS",
    "message" to "이메일 또는 비밀번호가 일치하지 않습니다."
)

@OptIn(ExperimentalKtorApi::class)
fun AuthApi(userAuthService: AuthService) = ApiRoute("auth") {
    post("login") {
        try {
            val request = call.receive<UserLoginRequest>()
            val response = userAuthService.login(request)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.Unauthorized, loginBadCredentialsResponse)
        } catch (e: UserValidationException.WrongPasswordException) {
            call.respond(HttpStatusCode.Unauthorized, loginBadCredentialsResponse)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Auth")
        summary = "로그인"
        description = "이메일/비밀번호로 로그인하고 JWT 토큰을 발급"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<UserLoginRequest>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "로그인 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<UserLoginResponse>()
                }
            }
            HttpStatusCode.Unauthorized {
                description = "이메일 또는 비밀번호가 일치하지 않음 (code=BAD_CREDENTIALS)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
    post("logout") {
        try {
            val response = userAuthService.logout()
            call.respond(HttpStatusCode.OK, response)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Auth")
        summary = "로그아웃"
        responses {
            HttpStatusCode.OK {
                description = "로그아웃 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<UserLogoutResponse>()
                }
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
}
