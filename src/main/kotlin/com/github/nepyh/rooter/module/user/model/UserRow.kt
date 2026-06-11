package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime


object UserTable : IntIdTable("users") {
    val email = varchar("email", 320).uniqueIndex()
    val username = varchar("username", 12).uniqueIndex()
    val password = varchar("password", 255)
    val avatarImageKey = varchar("avatar_image_key", 255).nullable()
    val bio = varchar("bio", 500).nullable()
    val createdAt = datetime("created_at")
}

class UserRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserRow>(UserTable)

    var email by UserTable.email
    var username by UserTable.username
    var password by UserTable.password
    var avatarImageKey by UserTable.avatarImageKey
    var bio by UserTable.bio
    var createdAt by UserTable.createdAt
}
