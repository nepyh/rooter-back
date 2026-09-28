package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
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
import java.time.LocalTime
import java.time.OffsetDateTime


class ExposedUserRepo : UserRepo {

    override fun insertUser(
        email: String,
        username: String,
        password: String,
        avatarImageKey: String?,
        bio: String?
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

    override fun findUserByEmail(email: String): UserRow? {
        return transaction {
            UserRow.find { UserTable.email eq email }
                .singleOrNull()
        }
    }

    override fun findUserById(id: Int): UserRow? {
        return transaction {
            UserRow.findById(id)
        }
    }

    override fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.avatarImageKey = avatarImageKey
            user
        }
    }

    override fun updateProfile(userId: Int, username: String?, bio: String?): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            username?.let { user.username = it }
            bio?.let { user.bio = it }
            user
        }
    }

    override fun updatePassword(userId: Int, hashedPassword: String): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.password = hashedPassword
            user
        }
    }

    override fun incrementTokenVersion(userId: Int): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw UserNotFoundException()
            user.tokenVersion += 1
            user
        }
    }

    override fun findStudentProfileByUserId(userId: Int): StudentProfileRow? {
        return transaction {
            val user = UserRow.findById(userId) ?: return@transaction null
            StudentProfileRow.find { StudentProfileTable.user eq user.id }
                .singleOrNull()
        }
    }

    override fun findUnavailableTimesByUserId(userId: Int): List<UnavailableTimeRow> {
        return transaction {
            val user = UserRow.findById(userId) ?: return@transaction emptyList()
            UnavailableTimeRow.find { UnavailableTimeTable.user eq user.id }
                .toList()
        }
    }

    override fun insertStudentProfile(
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

    override fun findTaskRowsByDateRange(userId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, List<ResultRow>> {
        return transaction {
            (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where {
                    (PlanBoardTable.userId eq userId) and
                        (DailyPlanTable.planDate greaterEq start) and
                        (DailyPlanTable.planDate lessEq end)
                }
                .groupBy { it[DailyPlanTable.planDate] }
        }
    }

    override fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime
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

    override fun deleteUnavailableTime(userId: Int, timeId: Int): Int {
        return transaction {
            val targets = UnavailableTimeRow.find {
                (UnavailableTimeTable.id eq timeId) and (UnavailableTimeTable.user eq userId)
            }.toList()
            targets.forEach { it.delete() }
            targets.size
        }
    }
}
