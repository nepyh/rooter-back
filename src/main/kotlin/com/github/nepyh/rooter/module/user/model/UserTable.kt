package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

object UserTable : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 320)
    val userName = varchar("username", 12)
    val password = varchar("password", 255)
    val avatarImageKey = varchar("avatar_image_key", 255).nullable()
    val bio = varchar("bio", 500).nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}