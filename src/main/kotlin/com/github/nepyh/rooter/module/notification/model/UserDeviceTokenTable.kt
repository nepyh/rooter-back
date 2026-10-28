package com.github.nepyh.rooter.module.notification.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone


/**
 * 기기별 Expo 푸시 토큰 (rooter-ddl #43). 토큰은 앱 설치마다 하나라 전체에서 유일하다 —
 * 같은 폰에서 다른 계정으로 로그인하면 행을 새로 만들지 않고 user_id 를 옮긴다.
 */
object UserDeviceTokenTable : IntIdTable("user_device_tokens") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val token = varchar("token", 255).uniqueIndex()
    val platform = enumerationByName<DevicePlatform>("platform", 10)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val lastSeenAt = timestampWithTimeZone("last_seen_at").defaultExpression(CurrentTimestampWithTimeZone)
}

class UserDeviceTokenRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserDeviceTokenRow>(UserDeviceTokenTable)

    var user by UserRow referencedOn UserDeviceTokenTable.userId
    var token by UserDeviceTokenTable.token
    var platform by UserDeviceTokenTable.platform
    var createdAt by UserDeviceTokenTable.createdAt
    var lastSeenAt by UserDeviceTokenTable.lastSeenAt
}
