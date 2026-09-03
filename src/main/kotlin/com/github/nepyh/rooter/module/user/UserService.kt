package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import com.github.nepyh.rooter.module.user.dto.ChangePasswordRequest
import com.github.nepyh.rooter.module.user.dto.PasswordUpdateResponse
import com.github.nepyh.rooter.module.user.dto.StreakDayResponse
import com.github.nepyh.rooter.module.user.dto.StreakResponse
import com.github.nepyh.rooter.module.user.dto.UpdateProfileRequest
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserProfileUpdateResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import io.ktor.http.content.PartData
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
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

        // 비밀번호 형식 체크 (8자 이상, 숫자 + 영문 포함)
        val passwordRegex = Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")
        if (!passwordRegex.matches(request.password)) {
            throw UserValidationException.WrongPasswordFormatException()
        }

        // bcrypt 암호화
        val hashedPassword = BCrypt.hashpw(
            request.password,
            BCrypt.gensalt()
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
            schoolId = profile.schoolId,
            grade = profile.grade,
            classNumber = profile.classNumber,
            createdAt = user.createdAt.toString(),
            avatarImageKey = user.avatarImageKey,
            bio = user.bio
        )
    }

    fun updateProfile(userId: Int, request: UpdateProfileRequest): UserProfileUpdateResponse {
        request.username?.let {
            if (it.isBlank() || it.length > 12) {
                throw UserValidationException.WrongUsernameException()
            }
        }
        request.bio?.let {
            if (it.length > 500) {
                throw UserValidationException.WrongBioLengthException()
            }
        }

        val row = userRepo.updateProfile(
            userId = userId,
            username = request.username,
            bio = request.bio
        )

        return UserProfileUpdateResponse(
            id = row.id.value,
            username = row.username,
            bio = row.bio
        )
    }

    fun changePassword(userId: Int, request: ChangePasswordRequest): PasswordUpdateResponse {
        val user = userRepo.findUserById(userId) ?: throw UserNotFoundException()

        if (!BCrypt.checkpw(request.currentPassword, user.password)) {
            throw UserValidationException.WrongCurrentPasswordException()
        }

        val passwordRegex = Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")
        if (!passwordRegex.matches(request.newPassword)) {
            throw UserValidationException.WrongPasswordFormatException()
        }

        val hashedPassword = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
        userRepo.updatePassword(userId, hashedPassword)

        return PasswordUpdateResponse(message = "비밀번호가 변경되었습니다.")
    }

    fun getStreak(userId: Int, start: LocalDate, end: LocalDate): StreakResponse {
        if (end.isBefore(start)) {
            throw UserValidationException.WrongDateRangeException()
        }
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val tasksByDate = userRepo.findTaskRowsByDateRange(userId, start, end)

        val days = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .map { date ->
                val rows = tasksByDate[date].orEmpty()
                val totalTasks = rows.size
                val completedTasks = rows.count { it[PlanTasks.isCompleted] }
                val completionRate = if (totalTasks == 0) 0.0 else (completedTasks.toDouble() / totalTasks) * 100

                StreakDayResponse(date = date.toString(), completionRate = completionRate)
            }
            .toList()

        return StreakResponse(days = days)
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
            classNumber = request.classNumber
        )

        return StudentProfileResponse(
            id = row.id.value,
            userId = userId,
            schoolId = row.schoolId,
            grade = row.grade,
            classNumber = row.classNumber
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

        val dayOfWeek = try {
            DayOfWeek.fromCode(request.dayOfWeek)
        } catch (_: IllegalArgumentException) {
            throw UserValidationException.WrongDayOfWeekException()
        }

        val row = userRepo.insertUnavailableTime(
            userId = userId,
            dayOfWeek = dayOfWeek,
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