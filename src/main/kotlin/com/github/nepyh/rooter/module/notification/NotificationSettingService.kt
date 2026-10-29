package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationException
import com.github.nepyh.rooter.module.notification.model.DEFAULT_TASK_REMINDER_ENABLED
import com.github.nepyh.rooter.module.notification.model.DEFAULT_TASK_REMINDER_LEAD_MINUTES
import com.github.nepyh.rooter.module.notification.model.MAX_TASK_REMINDER_LEAD_MINUTES
import com.github.nepyh.rooter.module.notification.model.NotificationSettingTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime


class NotificationSettingService {

    /** 행이 없으면 기본값. 4단계 할일 알림 Job 도 이 값으로 보낼지·언제 보낼지 정한다. 바깥 트랜잭션이 있으면 그 안에서 돈다. */
    fun get(userId: Int): NotificationSettingsResponse = transaction {
        NotificationSettingTable.selectAll()
            .where { NotificationSettingTable.id eq userId }
            .singleOrNull()
            ?.let {
                NotificationSettingsResponse(
                    taskReminderEnabled = it[NotificationSettingTable.taskReminderEnabled],
                    taskReminderLeadMinutes = it[NotificationSettingTable.taskReminderLeadMinutes].toInt()
                )
            }
            ?: DEFAULT
    }

    /** 보낸 필드만 바꾼다. 처음 바꾸는 사용자는 이때 행이 생긴다 (보내지 않은 필드는 기본값). */
    fun update(userId: Int, request: NotificationSettingsUpdateRequest): NotificationSettingsResponse {
        request.taskReminderLeadMinutes?.let {
            if (it !in 0..MAX_TASK_REMINDER_LEAD_MINUTES) throw NotificationException.InvalidLeadMinutesException()
        }

        return transaction {
            if (request.taskReminderEnabled == null && request.taskReminderLeadMinutes == null) {
                return@transaction get(userId)
            }
            val current = get(userId)
            val updated = NotificationSettingsResponse(
                taskReminderEnabled = request.taskReminderEnabled ?: current.taskReminderEnabled,
                taskReminderLeadMinutes = request.taskReminderLeadMinutes ?: current.taskReminderLeadMinutes
            )
            NotificationSettingTable.upsert {
                it[id] = userId
                it[taskReminderEnabled] = updated.taskReminderEnabled
                it[taskReminderLeadMinutes] = updated.taskReminderLeadMinutes.toShort()
                it[updatedAt] = OffsetDateTime.now()
            }
            updated
        }
    }

    companion object {
        val DEFAULT = NotificationSettingsResponse(
            taskReminderEnabled = DEFAULT_TASK_REMINDER_ENABLED,
            taskReminderLeadMinutes = DEFAULT_TASK_REMINDER_LEAD_MINUTES
        )
    }
}
