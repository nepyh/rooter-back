package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.content.PartData
import java.time.LocalTime

class UserService(
    private val userRepo: UserRepo,
    private val fileStorage: FileStorage
) {

    fun registerUser(request: UserRegisterRequest): UserRegisterResponse {

        // 사용자 이름 (12자 이하)
        val lengthUsername = request.username
        if (lengthUsername.length > 12) {
            throw UserValidationException.WrongUsernameException()
        }

        // 이메일 길이 체크 (users.email varchar(320))
        if (request.email.length > 320) {
            throw UserValidationException.WrongEmailLengthException()
        }

        // 이메일 중복 체크
        val existingUser = userRepo.findUserByEmail(request.email)
        if (existingUser != null) {
            throw UserValidationException.DuplicatedEmailException()
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

        // 학교 코드 길이 체크 (student_profiles.school_id char(10))
        if (request.schoolId.length > 10) {
            throw UserValidationException.WrongSchoolIdException()
        }

        val row = userRepo.insertStudentProfile(
            userId = userId,
            schoolId = request.schoolId,
            grade = request.grade,
            classNumber = request.classNumber,
            studyStyle = request.studyStyle
        )

        return StudentProfileResponse(
            id = row.id.value,
            userId = userId,
            schoolId = row.school,
            grade = row.grade,
            classNumber = row.classNumber,
            studyStyle = row.studyStyle
        )
    }

    suspend fun updateAvatar(userId: Int, file: PartData.FileItem): AvatarUpdateResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val avatarImageKey = fileStorage.upload(file, "avatars")
        userRepo.updateAvatarImageKey(userId, avatarImageKey)

        return AvatarUpdateResponse(
            userId = userId,
            avatarImageKey = avatarImageKey
        )
    }

    fun addUnavailableTime(userId: Int, request: UnavailableTimeRequest): UnavailableTimeResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        // 요일 범위 체크 (user_unavailable_times.day_of_week check 1~7)
        if (request.dayOfWeek < 1 || request.dayOfWeek > 7) {
            throw UserValidationException.WrongDayOfWeekException()
        }

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