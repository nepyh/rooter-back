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
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
fun AuthApi(authService: AuthService) = ApiRoute("auth") {
    post("login") {
        try {
            val request = call.receive<UserLoginRequest>()
            val response = authService.login(request)
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
        description = "이메일/비밀번호로 로그인하고 JWT 토큰을 발급 (발급 후 14일간 유효)"
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
    authenticate("auth-jwt") {
        post("logout") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val response = authService.logout(userId)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: UserNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Auth")
            summary = "로그아웃"
            description = "Authorization: Bearer 헤더로 전달된 JWT 가 유효해야 호출 가능. 로그아웃 시 해당 유저의 토큰 버전을 올려 그 시점 이전에 발급된 모든 토큰을 무효화함"
            responses {
                HttpStatusCode.OK {
                    description = "로그아웃 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<UserLogoutResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "Authorization 헤더 누락 또는 유효하지 않은 토큰"
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
