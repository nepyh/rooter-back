package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.exception.UserValidationException

/** 영문과 숫자를 하나 이상 포함한 8자 이상 */
private val PASSWORD_FORMAT = Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")

/**
 * 새로 설정하는 비밀번호(가입·변경)의 형식 검사. 공백 문자는 하나도 넣을 수 없다 (#385 — 실수로 넣은 띄어쓰기까지 저장되던 것).
 * 공백은 정규식 `\s` 대신 [Char.isWhitespace] 로 본다 — `\s` 는 한글 키보드의 전각 공백(U+3000)을 공백으로 보지 않는다.
 *
 * 로그인·현재 비밀번호 확인에는 쓰지 않는다 — 이미 공백째 저장된 기존 비밀번호도 그대로 통과해야 하므로.
 */
internal fun requireValidPasswordFormat(password: String) {
    if (!PASSWORD_FORMAT.matches(password) || password.any { it.isWhitespace() }) {
        throw UserValidationException.WrongPasswordFormatException()
    }
}
