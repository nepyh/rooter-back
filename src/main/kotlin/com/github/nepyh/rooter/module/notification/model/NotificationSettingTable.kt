package com.github.nepyh.rooter.module.notification.model

import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.Table

// DDL 에 없는 테이블: 알림 종류별 on/off 설정을 저장할 곳이 없어서 추가 (rooter-ddl 반영 필요, 별도 전달함)
object NotificationSettings : Table("notification_settings") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").uniqueIndex().references(UserTable.id)
    val taskReminderEnabled = bool("task_reminder_enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}
