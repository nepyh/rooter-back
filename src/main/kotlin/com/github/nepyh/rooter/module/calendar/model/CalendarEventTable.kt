package com.github.nepyh.rooter.module.calendar.model

import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

object CalendarEventTable : IntIdTable("calendar_events") {
    val userId = integer("user_id") // DDL 에 FK 없음 (users 테이블 소유 관계만 코드에서 관리)
    val title = varchar("title", 100)
    val eventDate = date("event_date")
    val memo = varchar("memo", 500).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class CalendarEventRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<CalendarEventRow>(CalendarEventTable)

    var userId by CalendarEventTable.userId
    var title by CalendarEventTable.title
    var eventDate by CalendarEventTable.eventDate
    var memo by CalendarEventTable.memo
    var createdAt by CalendarEventTable.createdAt
}
