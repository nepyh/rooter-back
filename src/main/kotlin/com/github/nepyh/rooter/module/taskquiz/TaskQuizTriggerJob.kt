package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import kotlinx.serialization.Serializable

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

@Serializable
private data class TaskQuizJobPayload(val planTaskId: Int, val attemptNumber: Int, val taskName: String)

/**
 * 태스크 종료 시각이 지나면 완료(is_completed) 여부와 상관없이 완료 확인 퀴즈를 자동 생성한다.
 * 퀴즈 자체가 사실상 완료 확인 수단: 통과하면 자동으로 완료 처리되고, 직전 시도가 불합격이면
 * RETRY_DELAY_MINUTES(10)분 뒤 재시도를 자동 생성한다 (최초 1회 + 재시도 2회, MAX_ATTEMPTS(3)).
 */
class TaskQuizTriggerJob(
    private val taskQuizService: TaskQuizService
) : SchedulerJob {

    override val jobType: String = "task_quiz_trigger"

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> = transaction {
        val zonedNow = now.atZoneSameInstant(ZONE)
        val today = zonedNow.toLocalDate()

        val initialJobs = findInitialTriggers(today, zonedNow)
        val retryJobs = findRetryTriggers(zonedNow)

        initialJobs + retryJobs
    }

    private fun findInitialTriggers(today: LocalDate, zonedNow: ZonedDateTime): List<DueJob> {
        // FK id 만 뽑아 쓰는 조회라 DAO 대신 Table DSL 을 쓴다 (Row 로 읽으면 행마다 참조 엔티티를 추가 조회).
        val alreadyStarted = TaskQuizAttemptTable.selectAll()
            .where { TaskQuizAttemptTable.attemptNumber eq 1 }
            .map { it[TaskQuizAttemptTable.planTaskId].value }
            .toSet()

        return (PlanTaskTable innerJoin DailyPlanTable)
            .selectAll()
            .where { DailyPlanTable.planDate eq today }
            .mapNotNull { row ->
                val planTaskId = row[PlanTaskTable.id].value
                if (planTaskId in alreadyStarted) return@mapNotNull null

                val endAt = ZonedDateTime.of(today, row[PlanTaskTable.endTime], ZONE)
                if (endAt.isAfter(zonedNow)) return@mapNotNull null

                val payload = TaskQuizJobPayload(planTaskId, 1, row[PlanTaskTable.taskName])
                DueJob(
                    runKey = "task_quiz_${planTaskId}_1",
                    scheduledAt = endAt.toOffsetDateTime(),
                    payload = json.encodeToString(TaskQuizJobPayload.serializer(), payload)
                )
            }
    }

    private fun findRetryTriggers(zonedNow: ZonedDateTime): List<DueJob> {
        // 태스크별로 "가장 최근(attemptNumber 최댓값)" attempt만 봐야 한다 — passed=false 로만 필터링하면
        // 그 뒤에 이미 새 attempt(성공/실패 불문)가 생겼는데도 옛날 실패 attempt가 다시 잡히는 버그가 생김.
        // 전체 attempt 를 스캔해 태스크별 최신 attempt 만 골라내는 집계성 조회라 Table DSL 을 유지한다.
        val latestByTask = TaskQuizAttemptTable.selectAll()
            .orderBy(TaskQuizAttemptTable.attemptNumber to SortOrder.DESC)
            .groupBy { it[TaskQuizAttemptTable.planTaskId].value }
            .mapValues { (_, rows) -> rows.first() }

        return latestByTask.values.mapNotNull { row ->
            if (row[TaskQuizAttemptTable.passed] != false) return@mapNotNull null // null(미제출) 또는 true(통과)면 재시도 대상 아님

            val attemptNumber = row[TaskQuizAttemptTable.attemptNumber]
            if (attemptNumber >= MAX_ATTEMPTS) return@mapNotNull null

            val retryAt = row[TaskQuizAttemptTable.createdAt].atZoneSameInstant(ZONE).plusMinutes(RETRY_DELAY_MINUTES)
            if (retryAt.isAfter(zonedNow)) return@mapNotNull null

            val planTaskId = row[TaskQuizAttemptTable.planTaskId].value
            val taskName = PlanTaskRow.findById(planTaskId)?.taskName ?: return@mapNotNull null

            val nextAttempt = attemptNumber + 1
            val payload = TaskQuizJobPayload(planTaskId, nextAttempt, taskName)
            DueJob(
                runKey = "task_quiz_${planTaskId}_$nextAttempt",
                scheduledAt = retryAt.toOffsetDateTime(),
                payload = json.encodeToString(TaskQuizJobPayload.serializer(), payload)
            )
        }
    }

    override suspend fun execute(due: DueJob) {
        val payload = json.decodeFromString(TaskQuizJobPayload.serializer(), due.payload)
        taskQuizService.generateAttempt(payload.planTaskId, payload.attemptNumber, payload.taskName)
    }
}
