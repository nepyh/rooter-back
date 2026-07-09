package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    fun findUserByUsername(username: String): UserRow? {
        return transaction {
            UserRow.find { UserTable.username eq username }
                .singleOrNull()
        }
    }

    fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow {
        return transaction {
            val user = UserRow.findById(userId)
                ?: throw com.github.nepyh.rooter.module.user.exception.UserNotFoundException()
            user.avatarImageKey = avatarImageKey
            user
        }
    }

    fun findStudentProfileByUserId(userId: Int): StudentProfileRow? {
        return transaction {
            val user = UserRow.findById(userId) ?: return@transaction null
            StudentProfileRow.find { com.github.nepyh.rooter.module.user.model.StudentProfileTable.user eq user.id }
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
        classNumber: Int,
        studyStyle: String? = null
    ): StudentProfileRow {
        return transaction {
            val user = UserRow.findById(userId) ?: throw com.github.nepyh.rooter.module.user.exception.UserNotFoundException()
            StudentProfileRow.new {
                this.user = user
                this.schoolId = schoolId
                this.grade = grade
                this.classNumber = classNumber
                this.studyStyle = studyStyle
            }
        }
    }

    fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: Short,
        startTime: java.time.LocalTime,
        endTime: java.time.LocalTime
    ): UnavailableTimeRow {
        return transaction {
            val user = UserRow.findById(userId) ?: throw com.github.nepyh.rooter.module.user.exception.UserNotFoundException()
            UnavailableTimeRow.new {
                this.user = user
                this.dayOfWeek = dayOfWeek
                this.startTime = startTime
                this.endTime = endTime
            }
        }
    }
}