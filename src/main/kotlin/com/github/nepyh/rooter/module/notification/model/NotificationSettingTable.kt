package com.github.nepyh.rooter.module.notification.model

import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone


/**
 * 사용자별 알림 설정 (rooter-ddl #43). PK 가 user_id 라 사용자당 1행이고,
 * 행이 없으면 기본값([DEFAULT_TASK_REMINDER_ENABLED], [DEFAULT_TASK_REMINDER_LEAD_MINUTES]) — 설정을 바꾼 사용자만 행이 생긴다.
 */
object NotificationSettingTable : IdTable<Int>("notification_settings") {
    override val id: Column<EntityID<Int>> = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(id)

    val taskReminderEnabled = bool("task_reminder_enabled").default(DEFAULT_TASK_REMINDER_ENABLED)
    val taskReminderLeadMinutes = short("task_reminder_lead_minutes").default(DEFAULT_TASK_REMINDER_LEAD_MINUTES.toShort())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}

class NotificationSettingRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NotificationSettingRow>(NotificationSettingTable)

    var taskReminderEnabled by NotificationSettingTable.taskReminderEnabled
    var taskReminderLeadMinutes by NotificationSettingTable.taskReminderLeadMinutes
    var updatedAt by NotificationSettingTable.updatedAt
}

const val DEFAULT_TASK_REMINDER_ENABLED = true
const val DEFAULT_TASK_REMINDER_LEAD_MINUTES = 5
const val MAX_TASK_REMINDER_LEAD_MINUTES = 60 // DDL: chk_notification_settings_lead_minutes (0~60)
