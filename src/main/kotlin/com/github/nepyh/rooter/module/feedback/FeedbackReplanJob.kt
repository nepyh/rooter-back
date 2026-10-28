package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

@Serializable
private data class FeedbackReplanPayload(val planBoardId: Int, val planDate: String)

/**
 * 자정 배치. 직전에 끝난 하루(오늘 - 1일)에 제출된 설문을 모아, (플랜보드, 날짜)마다 조정(AI 보충 태스크)을 1회 실행한다.
 *
 * 설문 제출마다 AI 를 부르지 않고 하루 1회로 모은다 — 조정 산출물은 미래 날짜에 붙으므로 자정까지 늦어도 무해하다.
 * 설문이 0건인 날짜·보드는 후보에서 빠진다(조정 트리거 자체가 없음).
 * 자정 시각을 놓쳐도 그날 안에 서버가 살아 있으면 실행된다 — 매 틱 후보를 다시 내지만, 이미 청구한
 * (job_type, run_key) 는 엔진이 claim 전에 걸러내므로 보드 하나가 하루 종일 헛 INSERT 를 만들지 않는다.
 */
class FeedbackReplanJob(
    private val feedbackService: FeedbackService
) : SchedulerJob {

    override val jobType: String = "feedback_replan"

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> = transaction {
        val target = now.atZoneSameInstant(ZONE).toLocalDate().minusDays(1)

        // daily_feedback 이 1건 이상 달린 (plan_board_id, plan_date) 조합만 후보가 된다.
        val submittedDays = (DailyFeedbackTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { DailyPlanTable.planDate eq target }
            .map { it[PlanBoardTable.id].value to it[DailyPlanTable.planDate] }
            .distinct()

        submittedDays.map { (planBoardId, planDate) ->
            DueJob(
                runKey = "feedback_replan_${planDate}_${planBoardId}",
                // 그 하루가 끝난 다음날 00:00 (KST)
                scheduledAt = planDate.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),
                payload = json.encodeToString(
                    FeedbackReplanPayload.serializer(),
                    FeedbackReplanPayload(planBoardId, planDate.toString())
                )
            )
        }
    }

    override suspend fun execute(due: DueJob) {
        val payload = json.decodeFromString(FeedbackReplanPayload.serializer(), due.payload)
        val planDate = LocalDate.parse(payload.planDate)
        // payload 에는 보드와 날짜만 담는다 — 조정에 필요한 사용자 id 는 보드 소유자에서 얻는다.
        val userId = transaction { PlanBoardRow.findById(payload.planBoardId)?.user?.id?.value } ?: return

        feedbackService.replanForDay(userId, payload.planBoardId, planDate)
    }
}
