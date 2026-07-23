package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationValidationException
import com.github.nepyh.rooter.module.notification.model.TaskReminderLogs
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokens
import com.github.nepyh.rooter.module.notification.push.PushSender
import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalTime

class NotificationService(private val pushSender: PushSender) {

    companion object {
        private const val REMINDER_LEAD_MINUTES = 5L
    }

    suspend fun registerDeviceToken(userId: Int, request: DeviceTokenRegisterRequest) {
        val platform = request.platform.uppercase()
        if (platform != "ANDROID" && platform != "IOS") {
            throw NotificationValidationException.InvalidPlatformException()
        }

        newSuspendedTransaction {
            val existing = UserDeviceTokens.selectAll()
                .where { UserDeviceTokens.token eq request.token }
                .firstOrNull()

            if (existing != null) {
                UserDeviceTokens.update({ UserDeviceTokens.token eq request.token }) {
                    it[this.userId] = userId
                    it[this.platform] = platform
                }
            } else {
                UserDeviceTokens.insert {
                    it[this.userId] = userId
                    it[this.token] = request.token
                    it[this.platform] = platform
                }
            }
        }
    }

    // 1분마다 호출됨 - "지금부터 5분 후"에 시작하는, 아직 안 끝난 태스크를 찾아 알림
    suspend fun sendDueReminders() {
        val today = LocalDate.now()
        val windowStart = LocalTime.now().plusMinutes(REMINDER_LEAD_MINUTES).withSecond(0).withNano(0)
        val windowEnd = windowStart.plusMinutes(1)

        val dueTasks = newSuspendedTransaction {
            (PlanTasks innerJoin DailyPlans innerJoin PlanBoards)
                .selectAll()
                .where {
                    (DailyPlans.planDate eq today) and
                        (PlanTasks.startTime greaterEq windowStart) and
                        (PlanTasks.startTime less windowEnd) and
                        (PlanTasks.isCompleted eq false)
                }
                .map { Triple(it[PlanTasks.id], it[PlanBoards.userId], it[PlanTasks.taskName]) }
        }

        for ((planTaskId, userId, taskName) in dueTasks) {
            val claimed = newSuspendedTransaction {
                val alreadySent = TaskReminderLogs.selectAll()
                    .where { TaskReminderLogs.planTaskId eq planTaskId }
                    .firstOrNull() != null

                if (alreadySent) {
                    false
                } else {
                    TaskReminderLogs.insert { it[this.planTaskId] = planTaskId }
                    true
                }
            }

            if (!claimed) continue

            val tokens = newSuspendedTransaction {
                UserDeviceTokens.selectAll()
                    .where { UserDeviceTokens.userId eq userId }
                    .map { it[UserDeviceTokens.token] }
            }

            tokens.forEach { token ->
                pushSender.send(token, "곧 공부 시간이에요!", "${REMINDER_LEAD_MINUTES}분 후 '$taskName' 시작 시간입니다.")
            }
        }
    }
}
