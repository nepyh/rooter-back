package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime

class UserRepo {

    fun insertUser(
        email: String,
        username: String,
        password: String,
        avatarImageKey: String? = null,
        bio: String? = null
    ): UserRow {
        return transaction {
            UserRow.new {
                this.email = email
                this.username = username
                this.password = password
                this.avatarImageKey = avatarImageKey
                this.bio = bio
                this.createdAt = OffsetDateTime.now()
            }
        }
    }

    fun findUserByEmail(email: String): UserRow? {
        return transaction {
            UserRow.find { UserTable.email eq email }
                .singleOrNull()
        }
    }

    fun findUserById(id: Int): UserRow? {
        return transaction {
            UserRow.findById(id)
        }
    }

    fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.avatarImageKey = avatarImageKey
            user
        }
    }

    fun updateProfile(userId: Int, username: String?, bio: String?): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            username?.let { user.username = it }
            bio?.let { user.bio = it }
            user
        }
    }

    fun updatePassword(userId: Int, hashedPassword: String): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.password = hashedPassword
            user
        }
    }

    fun incrementTokenVersion(userId: Int): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.tokenVersion += 1
            user
        }
    }

    fun findStudentProfileByUserId(userId: Int): StudentProfileRow? {
        return transaction {
            val user = UserRow.findById(userId) ?: return@transaction null
            StudentProfileRow.find { StudentProfileTable.user eq user.id }
                .singleOrNull()
        }
    }

    fun findUnavailableTimesByUserId(userId: Int): List<UnavailableTimeRow> {
        return transaction {
            val user = UserRow.findById(userId) ?: return@transaction emptyList()
            UnavailableTimeRow.find { UnavailableTimeTable.user eq user.id }
                .toList()
        }
    }

    fun insertStudentProfile(
        userId: Int,
        schoolId: String,
        grade: Int,
        classNumber: Int
    ): StudentProfileRow {
        return transaction {
            val user = UserRow.findById(userId) ?: throw UserNotFoundException()
            StudentProfileRow.new {
                this.user = user
                this.schoolId = schoolId
                this.grade = grade
                this.classNumber = classNumber
            }
        }
    }

    fun findTaskRowsByDateRange(userId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, List<ResultRow>> {
        return transaction {
            (PlanTasks innerJoin DailyPlans innerJoin PlanBoards)
                .selectAll()
                .where {
                    (PlanBoards.userId eq userId) and
                        (DailyPlans.planDate greaterEq start) and
                        (DailyPlans.planDate lessEq end)
                }
                .groupBy { it[DailyPlans.planDate] }
        }
    }

    fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: DayOfWeek,
        startTime: java.time.LocalTime,
        endTime: java.time.LocalTime
    ): UnavailableTimeRow {
        return transaction {
            val user = UserRow.findById(userId) ?: throw UserNotFoundException()
            UnavailableTimeRow.new {
                this.user = user
                this.dayOfWeek = dayOfWeek
                this.startTime = startTime
                this.endTime = endTime
            }
        }
    }
}
