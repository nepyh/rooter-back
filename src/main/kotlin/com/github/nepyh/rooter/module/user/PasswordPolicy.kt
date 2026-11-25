package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.exception.UserValidationException

/**
 * 새 비밀번호 길이. 최대는 BCrypt 가 앞 72바이트만 쓰기 때문이다 — 넘으면 앞 72바이트가 같은 다른 비밀번호로도 로그인된다.
 * 아래에서 보조 평면 문자(이모지 등)를 막아 한 글자가 UTF-8 로 최대 3바이트(한글)이므로 20자 × 3 = 60바이트로 72 안에 든다.
 */
internal const val PASSWORD_MIN_LENGTH = 8
internal const val PASSWORD_MAX_LENGTH = 20

/** 영문과 숫자를 하나 이상 포함 */
private val LETTER_AND_DIGIT = Regex("^(?=.*[A-Za-z])(?=.*\\d).*$", RegexOption.DOT_MATCHES_ALL)

/**
 * 새로 설정하는 비밀번호(가입·변경)의 형식 검사. 어기면 모두 같은 code `INVALID_PASSWORD_FORMAT`.
 *
 * - 영문·숫자를 하나 이상 포함한 8~20자
 * - 공백·제어 문자 불가 (#385 — 실수로 넣은 띄어쓰기까지 저장되던 것). 정규식 `\s` 대신 [Char.isWhitespace] 로 본다 —
 *   `\s` 는 전각 공백(U+3000)·NBSP 를 공백으로 보지 않는다
 * - 눈에 안 보이는 서식 문자 불가 — 폭 없는 공백(U+200B)·BOM(U+FEFF) 등. 메신저·메모 앱에서 붙여넣을 때 섞여 들어와
 *   다음 로그인에 직접 치면 틀린다
 * - 이모지 불가 — 보조 평면 문자(대부분의 이모지, 서로게이트 쌍), 기타 기호(☺❤✅ 등 BMP 이모지), 이모지 변형 선택자·키캡
 *   (U+FE00~FE0F, U+20E3). 보조 평면 문자를 막아 글자 수(`length`)가 사용자가 보는 글자 수와 같아진다
 *
 * 로그인·현재 비밀번호 확인에는 쓰지 않는다 — 이 규칙 전에 저장된 기존 비밀번호도 그대로 통과해야 하므로.
 */
internal fun requireValidPasswordFormat(password: String) {
    val valid = password.length in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH &&
        LETTER_AND_DIGIT.matches(password) &&
        password.none { it.isForbiddenInPassword() }
    if (!valid) {
        throw UserValidationException.WrongPasswordFormatException()
    }
}

private fun Char.isForbiddenInPassword(): Boolean = isWhitespace() || isInvisibleOrEmoji()

/** 제어·서식(폭 없는 공백 등)·이모지·사용자 정의·미할당 문자. 비밀번호와 사용자 이름(#407)이 함께 쓴다 */
internal fun Char.isInvisibleOrEmoji(): Boolean {
    if (this in '︀'..'️') return true // 이모지 변형 선택자
    return when (Character.getType(this).toByte()) {
        Character.CONTROL,
        Character.FORMAT, // U+200B~200D, U+2060, U+FEFF 등
        Character.SURROGATE, // 보조 평면 문자 (이모지 대부분)
        Character.OTHER_SYMBOL, // BMP 이모지·그림 기호
        Character.ENCLOSING_MARK, // 키캡 U+20E3
        Character.PRIVATE_USE,
        Character.UNASSIGNED -> true
        else -> false
    }
}
