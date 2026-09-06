package com.github.nepyh.rooter.module.user.exception

import io.ktor.http.HttpStatusCode

sealed class UserValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class DuplicatedEmailException : UserValidationException(
        HttpStatusCode.Conflict,
        "DUPLICATED_EMAIL",
        "이미 사용 중인 이메일입니다."
    )
    class WrongPasswordFormatException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_PASSWORD_FORMAT",
        "비밀번호 형식이 올바르지 않습니다."
    )
    class BadCredentialsException : UserValidationException(
        HttpStatusCode.Unauthorized,
        "BAD_CREDENTIALS",
        "이메일 또는 비밀번호가 일치하지 않습니다."
    )
    class WrongUsernameException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_USERNAME",
        "사용할 수 없는 사용자 이름입니다."
    )
    class WrongEmailLengthException : UserValidationException(
        HttpStatusCode.BadRequest,
        "EMAIL_TOO_LONG",
        "이메일은 320자를 초과할 수 없습니다."
    )
    class WrongSchoolIdException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_SCHOOL_ID",
        "학교 코드는 10자를 초과할 수 없습니다."
    )
    class WrongDayOfWeekException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DAY_OF_WEEK",
        "요일 값은 1~7 사이여야 합니다."
    )
    class WrongBioLengthException : UserValidationException(
        HttpStatusCode.BadRequest,
        "BIO_TOO_LONG",
        "소개는 500자를 초과할 수 없습니다."
    )
    class WrongCurrentPasswordException : UserValidationException(
        HttpStatusCode.Unauthorized,
        "WRONG_CURRENT_PASSWORD",
        "현재 비밀번호가 일치하지 않습니다."
    )
    class WrongDateFormatException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_FORMAT",
        "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class WrongDateRangeException : UserValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_RANGE",
        "start 는 end 보다 늦을 수 없습니다."
    )
    class InvalidSocialTokenException : UserValidationException(
        HttpStatusCode.Unauthorized,
        "INVALID_SOCIAL_TOKEN",
        "유효하지 않은 소셜 로그인 토큰입니다."
    )
    class SocialLoginNotConfiguredException : UserValidationException(
        HttpStatusCode.ServiceUnavailable,
        "SOCIAL_LOGIN_NOT_CONFIGURED",
        "아직 지원하지 않는 소셜 로그인입니다."
    )
}
