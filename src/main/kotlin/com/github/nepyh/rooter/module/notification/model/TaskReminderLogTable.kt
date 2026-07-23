package com.github.nepyh.rooter.module.notification.model

import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

// 태스크 하나당 알림은 딱 한 번만 (5분 전 알림 중복 발송 방지용 로그)
object TaskReminderLogs : Table("task_reminder_logs") {
    val id = integer("id").autoIncrement()
    val planTaskId = integer("plan_task_id").uniqueIndex() references PlanTasks.id
    val sentAt = datetime("sent_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
