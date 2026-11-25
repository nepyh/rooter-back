package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.exception.UserValidationException

internal const val USERNAME_MAX_LENGTH = 12

/**
 * 사용자 이름(가입·프로필 수정) 검사 (#407). 어기면 모두 같은 code `INVALID_USERNAME`, 사유는 message 로 나눈다.
 *
 * - 1~12자
 * - 공백 불가 — 앞뒤·중간 모두. 전각 공백(U+3000)·NBSP 도 [Char.isWhitespace] 로 막는다
 * - 눈에 안 보이는 문자(폭 없는 공백 U+200B·BOM 등)·이모지 불가 — 비밀번호(#385)와 같은 기준([isInvisibleOrEmoji]).
 *   보조 평면 문자를 막으므로 글자 수(`length`)가 사용자가 보는 글자 수와 같다
 *
 * 예전엔 가입은 12자 초과만, 프로필 수정은 빈 이름·12자 초과만 막아 `"김 철수"` 같은 이름이 저장됐다.
 */
internal fun requireValidUsername(username: String) {
    val reason = when {
        username.isEmpty() -> "이름을 입력해주세요."
        username.any { it.isWhitespace() } -> "이름에 띄어쓰기를 넣을 수 없습니다."
        username.any { it.isInvisibleOrEmoji() } -> "이름에 이모지나 보이지 않는 문자는 넣을 수 없습니다."
        username.length > USERNAME_MAX_LENGTH -> "이름은 ${USERNAME_MAX_LENGTH}자 이하여야 합니다."
        else -> return
    }
    throw UserValidationException.WrongUsernameException(reason)
}

/** 소셜 가입 때 이메일 앞부분으로 만드는 이름. [requireValidUsername] 을 늘 통과하게 허용 안 되는 문자를 빼고 자른다 */
internal fun usernameFromEmail(email: String): String =
    email.substringBefore("@")
        .filterNot { it.isWhitespace() || it.isInvisibleOrEmoji() }
        .take(USERNAME_MAX_LENGTH)
        .ifEmpty { "user" }
