package com.github.nepyh.rooter.module.user.exception

sealed class UserValidationException(message: String) : Exception(message) {
    class DuplicatedEmailException : UserValidationException("이미 사용 중인 이메일입니다.")
    class WrongPasswordFormatException : UserValidationException("비밀번호 형식이 올바르지 않습니다.")
    class WrongPasswordException : UserValidationException("비밀번호가 일치하지 않습니다.")
    class WrongUserNameException : UserValidationException("사용할 수 없는 사용자 이름입니다.")
}