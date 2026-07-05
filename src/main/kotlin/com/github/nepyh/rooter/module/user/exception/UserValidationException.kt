package com.github.nepyh.rooter.module.user.exception

sealed class UserValidationException(message: String) : Exception(message) {
    class DuplicatedEmailException : UserValidationException("이미 사용 중인 이메일입니다.")
    class WrongPasswordFormatException : UserValidationException("비밀번호 형식이 올바르지 않습니다.")
    class WrongPasswordException : UserValidationException("비밀번호가 일치하지 않습니다.")
    class WrongUsernameException : UserValidationException("사용할 수 없는 사용자 이름입니다.")
    class WrongUsernameAlreadyException : UserValidationException("이미 있는 사용자 이름입니다.")
    class WrongEmailLengthException : UserValidationException("이메일은 320자를 초과할 수 없습니다.")
    class WrongSchoolIdException : UserValidationException("학교 코드는 10자를 초과할 수 없습니다.")
    class WrongDayOfWeekException : UserValidationException("요일 값은 1~7 사이여야 합니다.")
}