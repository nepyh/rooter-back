package com.github.nepyh.rooter.module.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginResponse
import com.github.nepyh.rooter.module.user.exception.UserAuthException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import org.mindrot.jbcrypt.BCrypt

class AuthService(
    private val userRepo: UserRepo,
    private val userService: UserService
) {

    private val jwtSecret = System.getenv("JWT_SECRET")
    private val jwtIssuer = System.getenv("JWT_ISSUER")

    fun login(request: UserLoginRequest): UserLoginResponse {
        // 유저 조회
        val user = userRepo.findUserByEmail(request.email)
            ?: throw UserNotFoundException()

        // 비밀번호 검증
        if (!BCrypt.checkpw(request.password, user.password)) {
            throw UserValidationException.WrongPasswordException()
        }

        // JWT 토큰 발급
        val token = JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", user.id.value)
            .withClaim("email", user.email)
            .sign(Algorithm.HMAC256(jwtSecret))

        return UserLoginResponse(
            email = user.email,
            username = user.username,
            token = token
        )
    }

    fun auth(token: String): Int {
        return try {
            val verifier = JWT.require(Algorithm.HMAC256(jwtSecret))
                .withIssuer(jwtIssuer)
                .build()
            val decoded = verifier.verify(token)
            decoded.getClaim("userId").asInt()
        } catch (e: Exception) {
            throw UserAuthException()
        }
    }
}