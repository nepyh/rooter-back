package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.module.notification.model.DEFAULT_TASK_REMINDER_LEAD_MINUTES
import com.github.nepyh.rooter.module.notification.model.NotificationSettingTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Serializable
private data class TaskReminderPayload(val userId: Int, val planTaskId: Int, val taskName: String, val startTime: String)

/**
 * 할일 시작 [알림 설정의 몇 분] 전에 "곧 시작" 알림을 보낸다 (알림 4단계).
 *
 * - 대상: 아직 대기(PENDING) 중인 할일. 완료·실패 처리한 할일은 보내지 않는다
 * - 보낼 시각 = 시작 시각 - taskReminderLeadMinutes (설정 행이 없으면 5분). 그 시각이 지났고 시작 후 [LATE_LIMIT_MINUTES]분이
 *   지나기 전이면 보낸다 — 서버가 잠깐 멈췄다 돌아와도 이미 시작한 지 한참 된 할일에 "곧 시작"을 보내지 않게
 * - 알림을 꺼도 [NotificationService.notify] 가 알림함에는 남기고 푸시만 생략한다 (rooter-ddl notifications 주석)
 * - runKey 에 날짜·시작 시각을 넣는다: 할일 시간을 옮기면 새 시각으로 한 번 더 보내고, 알림 분을 바꿔도 같은 시각이면 다시 보내지 않는다
 *
 * [findDue] 는 1분마다 돈다. 알림은 최대 60분 전이라 오늘·내일(자정 직후 할일) 할일만 읽는다.
 */
class TaskReminderJob(
    private val notificationService: NotificationService
) : SchedulerJob {

    override val jobType: String = "task_reminder"

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> = transaction {
        val zonedNow = now.atZoneSameInstant(APP_ZONE)
        val today = zonedNow.toLocalDate()

        // 설정 행이 없는 사용자도 기본값으로 보내야 해서 설정은 left join 으로 붙인다
        (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .join(NotificationSettingTable, JoinType.LEFT, PlanBoardTable.userId, NotificationSettingTable.id)
            .select(
                PlanTaskTable.id,
                PlanTaskTable.taskName,
                PlanTaskTable.startTime,
                DailyPlanTable.planDate,
                PlanBoardTable.userId,
                NotificationSettingTable.taskReminderLeadMinutes
            )
            .where {
                (DailyPlanTable.planDate inList listOf(today, today.plusDays(1))) and
                    (PlanTaskTable.status eq PlanTaskStatus.PENDING)
            }
            .mapNotNull { row ->
                val startAt = ZonedDateTime.of(row[DailyPlanTable.planDate], row[PlanTaskTable.startTime], APP_ZONE)
                val leadMinutes = row.getOrNull(NotificationSettingTable.taskReminderLeadMinutes)?.toLong()
                    ?: DEFAULT_TASK_REMINDER_LEAD_MINUTES.toLong()
                val remindAt = startAt.minusMinutes(leadMinutes)
                if (zonedNow.isBefore(remindAt) || !zonedNow.isBefore(startAt.plusMinutes(LATE_LIMIT_MINUTES))) {
                    return@mapNotNull null
                }

                val planTaskId = row[PlanTaskTable.id].value
                val payload = TaskReminderPayload(
                    userId = row[PlanBoardTable.userId].value,
                    planTaskId = planTaskId,
                    taskName = row[PlanTaskTable.taskName],
                    startTime = row[PlanTaskTable.startTime].format(HH_MM)
                )
                DueJob(
                    runKey = "task_reminder_${planTaskId}_${startAt.toLocalDate()}_${startAt.toLocalTime().format(HH_MM)}",
                    scheduledAt = remindAt.toOffsetDateTime(),
                    payload = json.encodeToString(TaskReminderPayload.serializer(), payload)
                )
            }
    }

    override suspend fun execute(due: DueJob) {
        val payload = json.decodeFromString(TaskReminderPayload.serializer(), due.payload)
        notificationService.notify(
            userId = payload.userId,
            type = NotificationType.TASK_REMINDER,
            title = TITLE,
            body = "${payload.startTime}에 '${payload.taskName}' 시작해요",
            data = mapOf("planTaskId" to payload.planTaskId.toString())
        )
    }

    companion object {
        const val LATE_LIMIT_MINUTES = 2L // 알림 0분 전(시작 시각)도 1분 틱이 한 번은 잡도록 여유를 둔다
        const val TITLE = "곧 공부 시작 시간이에요"
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
