package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.LocalDate
import java.time.LocalTime


interface UserRepo {

    fun insertUser(
        email: String,
        username: String,
        password: String,
        avatarImageKey: String? = null,
        bio: String? = null
    ): UserRow

    fun findUserByEmail(email: String): UserRow?

    fun findUserById(id: Int): UserRow?

    fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow

    fun updateProfile(userId: Int, username: String?, bio: String?): UserRow

    fun updatePassword(userId: Int, hashedPassword: String): UserRow

    fun incrementTokenVersion(userId: Int): UserRow

    fun findStudentProfileByUserId(userId: Int): StudentProfileRow?

    fun findUnavailableTimesByUserId(userId: Int): List<UnavailableTimeRow>

    fun insertStudentProfile(
        userId: Int,
        schoolId: String,
        grade: Int,
        classNumber: Int
    ): StudentProfileRow

    fun findTaskRowsByDateRange(userId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, List<ResultRow>>

    fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime
    ): UnavailableTimeRow

    /** 삭제된 row 수를 반환한다. 0이면 존재하지 않거나 본인 소유가 아님. */
    fun deleteUnavailableTime(userId: Int, timeId: Int): Int
}
