package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class UserRepo {

    fun insertUser(user: UserRow) {
        transaction {
            UserTable.insert {
                it[email] = user.email
                it[username] = user.username
                it[password] = user.password
                it[avatarImageKey] = user.avatarImageKey
                it[bio] = user.bio
                it[createdAt] = user.createdAt
            }
        }
    }

    fun findUserByEmail(email: String): UserRow? {
        return transaction {
            UserTable.selectAll()
                .where { UserTable.email eq email }
                .map { it.toUserRow() }
                .singleOrNull()
        }
    }

    private fun ResultRow.toUserRow(): UserRow {
        return UserRow(
            id = this[UserTable.id],
            email = this[UserTable.email],
            username = this[UserTable.username],
            password = this[UserTable.password],
            avatarImageKey = this[UserTable.avatarImageKey],
            bio = this[UserTable.bio],
            createdAt = this[UserTable.createdAt]
        )
    }
}