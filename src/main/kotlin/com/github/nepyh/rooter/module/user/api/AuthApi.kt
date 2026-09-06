package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.dto.SocialLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginResponse
import com.github.nepyh.rooter.module.user.dto.UserLogoutResponse
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


@OptIn(ExperimentalKtorApi::class)
fun AuthApi(authService: AuthService) = ApiRoute("auth") {
    post("login") {
        val request = call.receive<UserLoginRequest>()
        val response = authService.login(request)
        call.respond(HttpStatusCode.OK, response)
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
    post("google") {
        val request = call.receive<SocialLoginRequest>()
        val response = authService.loginWithGoogle(request.idToken)
        call.respond(HttpStatusCode.OK, response)
    }.describe {
        tag("Auth")
        summary = "Google 소셜 로그인"
        description = "Google 네이티브 SDK로 발급받은 id_token을 검증해 로그인/회원가입. 계정이 없으면 이메일 기준으로 자동 생성"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<SocialLoginRequest>()
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
                description = "유효하지 않은 id_token (code=INVALID_SOCIAL_TOKEN)"
            }
            HttpStatusCode.ServiceUnavailable {
                description = "Google 클라이언트 ID가 아직 설정되지 않음 (code=SOCIAL_LOGIN_NOT_CONFIGURED)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    post("apple") {
        val request = call.receive<SocialLoginRequest>()
        val response = authService.loginWithApple(request.idToken)
        call.respond(HttpStatusCode.OK, response)
    }.describe {
        tag("Auth")
        summary = "Apple 소셜 로그인"
        description = "Apple 네이티브 SDK로 발급받은 identityToken을 검증해 로그인/회원가입. 계정이 없으면 이메일 기준으로 자동 생성 (Apple이 email claim을 안 줄 수 있는 경우는 아직 미지원)"
        requestBody {
            ContentType.Application.Json {
                schema = jsonSchema<SocialLoginRequest>()
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
                description = "유효하지 않은 identityToken (code=INVALID_SOCIAL_TOKEN)"
            }
            HttpStatusCode.ServiceUnavailable {
                description = "Apple 클라이언트 ID가 아직 설정되지 않음 (code=SOCIAL_LOGIN_NOT_CONFIGURED)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    authenticate("auth-jwt") {
        post("logout") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val response = authService.logout(userId)
            call.respond(HttpStatusCode.OK, response)
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
