package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Serializable
private data class DailyFeedbackReminderPayload(val userId: Int, val dailyPlanId: Int)

/**
 * 매일 22:00 에 오늘 공부 설문을 아직 안 낸 학생에게 알림을 한 번 보낸다 (#374, 프론트 rooter-front#314).
 *
 * - 대상: 오늘 일일 계획(할일 1개 이상, 보관한 보드 제외)이 있고, 그날 일일 설문(plan_task_id IS NULL)을 아직 안 낸 학생.
 *   22시 전에 설문을 냈으면 보내지 않는다
 * - 보드가 여러 개라 오늘 계획이 여러 개여도 학생당 하루 한 번만 보낸다. 앱이 열 계획(data.dailyPlanId)은
 *   대표 보드의 계획을 먼저, 없으면 id 가 가장 작은 계획을 고른다. 어느 계획이든 일일 설문을 냈으면 그날은 끝난 것으로 본다
 * - 22:00 부터 [LATE_LIMIT_MINUTES]분 안에서만 후보로 낸다 — 서버가 그 시각에 멈춰 있다가 한참 뒤 돌아와서 늦은 밤에 보내지 않게
 * - runKey 에 학생·날짜를 넣어 하루 한 번만 보낸다
 * - 알림 설정은 할일 알림(taskReminderEnabled)을 따른다. 끄면 알림함에만 남고 푸시는 생략한다 ([NotificationService.notify])
 *
 * [findDue] 는 1분마다 돌지만 22:00 근처가 아니면 DB 를 읽지 않는다.
 */
class DailyFeedbackReminderJob(
    private val notificationService: NotificationService
) : SchedulerJob {

    override val jobType: String = "daily_feedback_reminder"
    override val monitored: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> {
        val zonedNow = now.atZoneSameInstant(APP_ZONE)
        val today = zonedNow.toLocalDate()
        val sendAt = ZonedDateTime.of(today, SEND_AT, APP_ZONE)
        if (zonedNow.isBefore(sendAt) || !zonedNow.isBefore(sendAt.plusMinutes(LATE_LIMIT_MINUTES))) return emptyList()

        return transaction {
            // 할일이 1개 이상 달린 오늘 계획 (보관한 보드 제외)
            val plans = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .select(DailyPlanTable.id, PlanBoardTable.userId, PlanBoardTable.isRepresentative)
                .where { (DailyPlanTable.planDate eq today) and (PlanBoardTable.isArchived eq false) }
                .withDistinct()
                .map { TodayPlan(it[DailyPlanTable.id].value, it[PlanBoardTable.userId].value, it[PlanBoardTable.isRepresentative]) }
            if (plans.isEmpty()) return@transaction emptyList()

            val submittedPlanIds = DailyFeedbackTable
                .select(DailyFeedbackTable.dailyPlanId)
                .where { (DailyFeedbackTable.dailyPlanId inList plans.map { it.dailyPlanId }) and DailyFeedbackTable.planTaskId.isNull() }
                .map { it[DailyFeedbackTable.dailyPlanId].value }
                .toSet()

            plans.groupBy { it.userId }
                .filterValues { userPlans -> userPlans.none { it.dailyPlanId in submittedPlanIds } }
                .map { (userId, userPlans) ->
                    val target = userPlans.sortedWith(compareBy({ !it.isRepresentative }, { it.dailyPlanId })).first()
                    DueJob(
                        runKey = "daily_feedback_reminder_${userId}_${today}",
                        scheduledAt = sendAt.toOffsetDateTime(),
                        payload = json.encodeToString(
                            DailyFeedbackReminderPayload.serializer(),
                            DailyFeedbackReminderPayload(userId, target.dailyPlanId)
                        )
                    )
                }
        }
    }

    override suspend fun execute(due: DueJob) {
        val payload = json.decodeFromString(DailyFeedbackReminderPayload.serializer(), due.payload)
        notificationService.notify(
            userId = payload.userId,
            type = NotificationType.DAILY_FEEDBACK_REMINDER,
            title = TITLE,
            body = BODY,
            data = mapOf("dailyPlanId" to payload.dailyPlanId.toString())
        )
    }

    private data class TodayPlan(val dailyPlanId: Int, val userId: Int, val isRepresentative: Boolean)

    companion object {
        /** 보내는 시각 (APP_ZONE) */
        val SEND_AT: LocalTime = LocalTime.of(22, 0)
        const val LATE_LIMIT_MINUTES = 5L
        const val TITLE = "오늘 공부 어떠셨나요?"
        const val BODY = "루티에게 오늘 공부를 알려주세요!"
    }
}
