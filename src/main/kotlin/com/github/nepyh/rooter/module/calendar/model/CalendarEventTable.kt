package com.github.nepyh.rooter.module.calendar.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

object CalendarEvents : Table("calendar_events") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
    val title = varchar("title", 100)
    val eventDate = date("event_date")
    val memo = varchar("memo", 500).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
