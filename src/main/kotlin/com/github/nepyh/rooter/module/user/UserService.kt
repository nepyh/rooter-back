package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserValidationException

class UserService(
    private val userRepo: UserRepo
) {

    fun registerUser(request: UserRegisterRequest): UserRegisterResponse {
        // 이메일 중복 체크
        val existingUser = userRepo.findUserByEmail(request.email)
        if (existingUser != null) {
            throw UserValidationException.DuplicatedEmailException()
        }

        // 사용자 이름 (12자 이하)
        val lengthUsername = request.username
        if (lengthUsername.length > 12) {
            throw UserValidationException.WrongUsernameException()
        }

        // 사용자 이름 중복 체크
        val existingUsername = userRepo.findUserByUsername(request.username)
        if (existingUsername != null) {
            throw UserValidationException.WrongUsernameAlreadyException()
        }

        // 비밀번호 형식 체크 (8자 이상, 숫자 + 영문 포함)
        val passwordRegex = Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")
        if (!passwordRegex.matches(request.password)) {
            throw UserValidationException.WrongPasswordFormatException()
        }

        // bcrypt 암호화
        val hashedPassword = org.mindrot.jbcrypt.BCrypt.hashpw(
            request.password,
            org.mindrot.jbcrypt.BCrypt.gensalt()
        )

        // DB 저장
        userRepo.insertUser(
            email = request.email,
            username = request.username,
            password = hashedPassword
        )

        return UserRegisterResponse(
            email = request.email,
            username = request.username
        )
    }
}