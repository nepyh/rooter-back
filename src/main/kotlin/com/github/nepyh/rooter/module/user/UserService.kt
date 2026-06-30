package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import java.time.LocalTime

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

    fun getUserInfo(id: Int): UserInfoResponse {
        val user = userRepo.findUserById(id) ?: throw UserNotFoundException()
        val profile = userRepo.findStudentProfileByUserId(id) ?: throw UserNotFoundException()

        return UserInfoResponse(
            id = user.id.value,
            username = user.username,
            email = user.email,
            schoolId = profile.school,
            grade = profile.grade,
            classNumber = profile.classNumber,
            createdAt = user.createdAt.toString()
        )
    }

    fun getUnavailableTimes(id: Int): List<UnavailableTimeResponse> {
        userRepo.findUserById(id) ?: throw UserNotFoundException()

        return userRepo.findUnavailableTimesByUserId(id).map { row ->
            UnavailableTimeResponse(
                id = row.id.value,
                dayOfWeek = row.dayOfWeek,
                startTime = row.startTime.toString(),
                endTime = row.endTime.toString()
            )
        }
    }

    fun createStudentProfile(userId: Int, request: StudentProfileRequest): StudentProfileResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val row = userRepo.insertStudentProfile(
            userId = userId,
            schoolId = request.schoolId,
            grade = request.grade,
            classNumber = request.classNumber
        )

        return StudentProfileResponse(
            id = row.id.value,
            userId = userId,
            schoolId = row.school,
            grade = row.grade,
            classNumber = row.classNumber
        )
    }

    fun addUnavailableTime(userId: Int, request: UnavailableTimeRequest): UnavailableTimeResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val row = userRepo.insertUnavailableTime(
            userId = userId,
            dayOfWeek = request.dayOfWeek,
            startTime = LocalTime.parse(request.startTime),
            endTime = LocalTime.parse(request.endTime)
        )

        return UnavailableTimeResponse(
            id = row.id.value,
            dayOfWeek = row.dayOfWeek,
            startTime = row.startTime.toString(),
            endTime = row.endTime.toString()
        )
    }
}