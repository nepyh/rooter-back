package com.github.nepyh.rooter.module.notification.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

object UserDeviceTokens : Table("user_device_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
    val token = varchar("token", 255).uniqueIndex()
    val platform = varchar("platform", 10) // "ANDROID" | "IOS"
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
