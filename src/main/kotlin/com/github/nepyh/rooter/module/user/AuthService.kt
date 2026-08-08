package com.github.nepyh.rooter.module.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginResponse
import com.github.nepyh.rooter.module.user.dto.UserLogoutResponse
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.temporal.ChronoUnit

class AuthService(
    private val userRepo: UserRepo,
    private val userService: UserService,
    val jwtSecret: String,
    val jwtIssuer: String
) {
    companion object {
        private const val TOKEN_EXPIRATION_DAYS = 14L
    }

    fun login(request: UserLoginRequest): UserLoginResponse {
        // 유저 조회 + 비밀번호 검증 (실패 시 동일 예외로 통합: 계정 존재 여부 노출 방지)
        val user = userRepo.findUserByEmail(request.email)
            ?: throw UserValidationException.BadCredentialsException()

        if (!BCrypt.checkpw(request.password, user.password)) {
            throw UserValidationException.BadCredentialsException()
        }

        // JWT 토큰 발급
        val token = JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", user.id.value)
            .withClaim("email", user.email)
            .withClaim("tokenVersion", user.tokenVersion)
            .withExpiresAt(Instant.now().plus(TOKEN_EXPIRATION_DAYS, ChronoUnit.DAYS))
            .sign(Algorithm.HMAC256(jwtSecret))

        return UserLoginResponse(
            email = user.email,
            username = user.username,
            token = token
        )
    }

    fun logout(userId: Int): UserLogoutResponse {
        userRepo.incrementTokenVersion(userId)
        return UserLogoutResponse(message = "Successfully logged out.")
    }
}