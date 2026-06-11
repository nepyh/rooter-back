package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDateTime

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
                this.createdAt = LocalDateTime.now()
            }
        }
    }

    fun findUserByEmail(email: String): UserRow? {
        return transaction {
            UserRow.find { UserTable.email eq email }
                .singleOrNull()
        }
    }
}