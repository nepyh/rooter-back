package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone


object UserTable : IntIdTable("users") {
    val email = varchar("email", 320).uniqueIndex()
    val username = varchar("username", 12)
    val password = char("password", 60)
    val avatarImageKey = varchar("avatar_image_key", 255).nullable()
    val bio = varchar("bio", 500).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val tokenVersion = integer("token_version").default(0)
    // 수면시간 (취침·기상). 둘 다 null = 설정 안 함 → 기본 23:00 / 06:30 (rooter-ddl 이슈 #29, PR #31 — nullable, 기본값 없음)
    val sleepTime = time("sleep_time").nullable()
    val wakeTime = time("wake_time").nullable()
    // 하교 후 귀가 시간(분, 0~180). null = 설정 안 함 → 기본 40분 (planboard/UserRoutine.kt)
    val commuteMinutes = short("commute_minutes").nullable()
}

class UserRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserRow>(UserTable)

    var email by UserTable.email
    var username by UserTable.username
    var password by UserTable.password
    var avatarImageKey by UserTable.avatarImageKey
    var bio by UserTable.bio
    var createdAt by UserTable.createdAt
    var tokenVersion by UserTable.tokenVersion
    var sleepTime by UserTable.sleepTime
    var wakeTime by UserTable.wakeTime
    var commuteMinutes by UserTable.commuteMinutes
}
