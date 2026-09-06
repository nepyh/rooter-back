package com.github.nepyh.rooter.module.user

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginResponse
import com.github.nepyh.rooter.module.user.dto.UserLogoutResponse
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.UserRow
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
private const val GOOGLE_ISSUER = "https://accounts.google.com"
private const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
private const val APPLE_ISSUER = "https://appleid.apple.com"

class AuthService(
    private val userRepo: UserRepo,
    private val userService: UserService,
    val jwtSecret: String,
    val jwtIssuer: String,
    private val googleClientId: String,
    private val appleClientId: String
) {
    companion object {
        private const val TOKEN_EXPIRATION_DAYS = 14L
    }

    private val googleVerifier by lazy { OidcTokenVerifier(GOOGLE_JWKS_URL) }
    private val appleVerifier by lazy { OidcTokenVerifier(APPLE_JWKS_URL) }

    fun login(request: UserLoginRequest): UserLoginResponse {
        // 유저 조회 + 비밀번호 검증 (실패 시 동일 예외로 통합: 계정 존재 여부 노출 방지)
        val user = userRepo.findUserByEmail(request.email)
            ?: throw UserValidationException.BadCredentialsException()

        if (!BCrypt.checkpw(request.password, user.password)) {
            throw UserValidationException.BadCredentialsException()
        }

        return issueToken(user)
    }

    fun loginWithGoogle(idToken: String): UserLoginResponse {
        if (googleClientId.isBlank()) throw UserValidationException.SocialLoginNotConfiguredException()

        val decoded = runCatching {
            googleVerifier.verify(idToken, issuer = GOOGLE_ISSUER, audience = googleClientId)
        }.getOrElse { throw UserValidationException.InvalidSocialTokenException() }

        val email = decoded.getClaim("email").asString()
        val emailVerified = decoded.getClaim("email_verified").asBoolean() ?: false
        if (email == null || !emailVerified) {
            throw UserValidationException.InvalidSocialTokenException()
        }

        return issueToken(findOrCreateSocialUser(email))
    }

    fun loginWithApple(idToken: String): UserLoginResponse {
        if (appleClientId.isBlank()) throw UserValidationException.SocialLoginNotConfiguredException()

        val decoded = runCatching {
            appleVerifier.verify(idToken, issuer = APPLE_ISSUER, audience = appleClientId)
        }.getOrElse { throw UserValidationException.InvalidSocialTokenException() }

        // Apple은 이메일을 프록시(private relay) 주소로 줄 수 있고, 최초 로그인 이후에는 id_token에
        // email claim이 아예 안 실릴 수도 있음. users 테이블에 apple sub 를 저장할 컬럼이 없어서
        // (스키마에 소셜 계정 식별자 컬럼이 없음, 반영 필요) 지금은 email claim이 있는 경우로만 제한.
        val email = decoded.getClaim("email").asString()
            ?: throw UserValidationException.InvalidSocialTokenException()

        return issueToken(findOrCreateSocialUser(email))
    }

    fun logout(userId: Int): UserLogoutResponse {
        userRepo.incrementTokenVersion(userId)
        return UserLogoutResponse(message = "Successfully logged out.")
    }

    private fun findOrCreateSocialUser(email: String): UserRow {
        userRepo.findUserByEmail(email)?.let { return it }

        val username = email.substringBefore("@").take(12).ifBlank { "user" }
        // 소셜 로그인 계정은 비밀번호로 로그인하지 않으므로, 절대 알아낼 수 없는 임의의 해시를 채워 넣는다
        // (users.password 가 NOT NULL 이라 비워둘 수 없음)
        val unusablePassword = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt())

        return userRepo.insertUser(email = email, username = username, password = unusablePassword)
    }

    private fun issueToken(user: UserRow): UserLoginResponse {
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
}
