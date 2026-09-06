package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationValidationException
import com.github.nepyh.rooter.module.notification.model.NotificationSettings
import com.github.nepyh.rooter.module.notification.model.TaskReminderLogs
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokens
import com.github.nepyh.rooter.module.notification.push.PushSender
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime

class NotificationService(private val pushSender: PushSender) {

    companion object {
        private const val REMINDER_LEAD_MINUTES = 5L
    }

    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

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

    fun getSettings(userId: Int): NotificationSettingsResponse {
        val enabled = NotificationSettings.selectAll()
            .where { NotificationSettings.userId eq userId }
            .firstOrNull()
            ?.get(NotificationSettings.taskReminderEnabled)
            ?: true // 설정을 아직 저장한 적 없으면 기본값(켜짐)

        return NotificationSettingsResponse(taskReminderEnabled = enabled)
    }

    fun updateSettings(userId: Int, request: NotificationSettingsUpdateRequest): NotificationSettingsResponse {
        val existing = NotificationSettings.selectAll()
            .where { NotificationSettings.userId eq userId }
            .firstOrNull()

        if (existing != null) {
            NotificationSettings.update({ NotificationSettings.userId eq userId }) {
                it[taskReminderEnabled] = request.taskReminderEnabled
            }
        } else {
            NotificationSettings.insert {
                it[this.userId] = userId
                it[taskReminderEnabled] = request.taskReminderEnabled
            }
        }

        return NotificationSettingsResponse(taskReminderEnabled = request.taskReminderEnabled)
    }

    // 1분마다 호출됨 - "지금부터 5분 후"에 시작하는, 아직 안 끝난 태스크를 찾아 알림
    suspend fun sendDueReminders() {
        val today = LocalDate.now()
        val windowStart = LocalTime.now().plusMinutes(REMINDER_LEAD_MINUTES).withSecond(0).withNano(0)
        val windowEnd = windowStart.plusMinutes(1)

        val dueTasks = newSuspendedTransaction {
            val candidates = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where {
                    (DailyPlanTable.planDate eq today) and
                        (PlanTaskTable.startTime greaterEq windowStart) and
                        (PlanTaskTable.startTime less windowEnd) and
                        (PlanTaskTable.isCompleted eq false)
                }
                .map { Triple(it[PlanTaskTable.id].value, it[PlanBoardTable.userId].value, it[PlanTaskTable.taskName]) }

            if (candidates.isEmpty()) return@newSuspendedTransaction candidates

            val optedOutUserIds = NotificationSettings.selectAll()
                .where { (NotificationSettings.userId inList candidates.map { it.second }) and (NotificationSettings.taskReminderEnabled eq false) }
                .map { it[NotificationSettings.userId] }
                .toSet()

            candidates.filter { it.second !in optedOutUserIds }
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
                runCatching {
                    pushSender.send(token, "곧 공부 시간이에요!", "${REMINDER_LEAD_MINUTES}분 후 '$taskName' 시작 시간입니다.")
                }.onFailure {
                    logger.warn("푸시 발송 실패 (planTaskId=$planTaskId)", it)
                }
            }
        }
    }
}
