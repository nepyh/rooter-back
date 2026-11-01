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
 * 알림함 (rooter-ddl #43). 서버가 보낸 모든 알림을 남긴다 — 푸시를 설정으로 꺼도 여기에는 남는다.
 * data 는 앱이 알림을 눌렀을 때 이동할 대상 등 부가 정보를 JSON 문자열로 담는다 (서버가 내용으로 조회하지 않음).
 */
object NotificationTable : IntIdTable("notifications") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val type = enumerationByName<NotificationType>("type", 40)
    val title = varchar("title", 100)
    val body = varchar("body", 500)
    val data = text("data").default("{}")
    val readAt = timestampWithTimeZone("read_at").nullable() // NULL = 안 읽음
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
}

class NotificationRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NotificationRow>(NotificationTable)

    var user by UserRow referencedOn NotificationTable.userId
    var type by NotificationTable.type
    var title by NotificationTable.title
    var body by NotificationTable.body
    var data by NotificationTable.data
    var readAt by NotificationTable.readAt
    var createdAt by NotificationTable.createdAt
}
