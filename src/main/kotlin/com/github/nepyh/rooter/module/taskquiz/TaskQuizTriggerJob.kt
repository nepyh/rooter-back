package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import kotlinx.serialization.Serializable

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

@Serializable
private data class TaskQuizJobPayload(val planTaskId: Int, val attemptNumber: Int, val taskName: String)

/** 후보 판정에 쓰는 할일 한 줄 (날짜·이름·종료 시각·상태). 퀴즈 기록과 붙이려고 id 로 찾을 수 있게 Map 에 담는다 */
private class DayTask(
    val planTaskId: Int,
    val date: LocalDate,
    val taskName: String,
    val endTime: LocalTime,
    val status: PlanTaskStatus
)

/**
 * 태스크 종료 시각이 지나면 완료 여부와 상관없이 완료 확인 퀴즈를 자동 생성한다 (실패(FAILED) 처리한 할일은 제외).
 * 퀴즈 자체가 사실상 완료 확인 수단: 통과하면 자동으로 완료 처리되고, 직전 시도가 불합격이면
 * RETRY_DELAY_MINUTES(10)분 뒤 재시도를 자동 생성한다 (최초 1회 + 재시도 2회, MAX_ATTEMPTS(3)).
 *
 * [findDue] 는 1분마다 돈다 — 읽는 범위를 "지금 후보가 될 수 있는 행" 으로 좁혀 둔다.
 * 1차는 오늘 할일, 재시도는 오늘·어제 할일(자정을 넘긴 재시도)만 본다. 퀴즈 기록은 지우지 않고 쌓이기만 하므로
 * 전체를 읽으면 시간이 갈수록 느려진다.
 */
class TaskQuizTriggerJob(
    private val taskQuizService: TaskQuizService
) : SchedulerJob {

    override val jobType: String = "task_quiz_trigger"

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> = transaction {
        val zonedNow = now.atZoneSameInstant(ZONE)
        val today = zonedNow.toLocalDate()

        // 1차는 오늘, 재시도는 자정을 넘긴 경우까지 봐야 하므로 오늘·어제 할일을 한 번만 읽고 둘 다에 쓴다
        val tasks = tasksBetween(today.minusDays(1), today)
        if (tasks.isEmpty()) return@transaction emptyList()

        findInitialTriggers(today, zonedNow, tasks) + findRetryTriggers(zonedNow, tasks)
    }

    private fun tasksBetween(from: LocalDate, to: LocalDate): Map<Int, DayTask> =
        (PlanTaskTable innerJoin DailyPlanTable)
            .select(
                PlanTaskTable.id,
                PlanTaskTable.taskName,
                PlanTaskTable.endTime,
                PlanTaskTable.status,
                DailyPlanTable.planDate
            )
            .where { DailyPlanTable.planDate inList listOf(from, to) }
            .associate { row ->
                val planTaskId = row[PlanTaskTable.id].value
                planTaskId to DayTask(
                    planTaskId = planTaskId,
                    date = row[DailyPlanTable.planDate],
                    taskName = row[PlanTaskTable.taskName],
                    endTime = row[PlanTaskTable.endTime],
                    status = row[PlanTaskTable.status]
                )
            }

    private fun findInitialTriggers(today: LocalDate, zonedNow: ZonedDateTime, tasks: Map<Int, DayTask>): List<DueJob> {
        val candidates = tasks.values
            .filter { it.date == today && it.status != PlanTaskStatus.FAILED } // 실패 처리한 태스크는 퀴즈를 만들지 않는다
            .mapNotNull { task ->
                val endAt = ZonedDateTime.of(today, task.endTime, ZONE)
                if (endAt.isAfter(zonedNow)) return@mapNotNull null

                task to endAt
            }
        if (candidates.isEmpty()) return emptyList()

        // 시작 여부는 후보(오늘 끝난 할일)로만 조회한다 — 예전엔 attempt 1 전체를 매분 읽었다
        val started = TaskQuizAttemptTable.select(TaskQuizAttemptTable.planTaskId)
            .where {
                (TaskQuizAttemptTable.planTaskId inList candidates.map { it.first.planTaskId }) and
                    (TaskQuizAttemptTable.attemptNumber eq 1)
            }
            .map { it[TaskQuizAttemptTable.planTaskId].value }
            .toSet()

        return candidates.filterNot { (task) -> task.planTaskId in started }.map { (task, endAt) ->
            val payload = TaskQuizJobPayload(task.planTaskId, 1, task.taskName)
            DueJob(
                runKey = "task_quiz_${task.planTaskId}_1",
                scheduledAt = endAt.toOffsetDateTime(),
                payload = json.encodeToString(TaskQuizJobPayload.serializer(), payload)
            )
        }
    }

    private fun findRetryTriggers(zonedNow: ZonedDateTime, tasks: Map<Int, DayTask>): List<DueJob> {
        // 재시도 지연이 RETRY_DELAY_MINUTES(10)분이라 대상 attempt 는 오늘·어제 할일에서만 나온다
        // (자정 직전 할일이 자정을 넘겨 재시도되는 경우까지 포함). 이틀보다 오래된 불합격은 지금 와서 재시도를
        // 만들 이유가 없으므로 후보에서 빠진다 — 퀴즈 기록 전체를 매분 읽지 않기 위한 범위이기도 하다.
        val attempts = TaskQuizAttemptTable.select(
            TaskQuizAttemptTable.planTaskId,
            TaskQuizAttemptTable.attemptNumber,
            TaskQuizAttemptTable.passed,
            TaskQuizAttemptTable.createdAt
        ).where { TaskQuizAttemptTable.planTaskId inList tasks.keys }

        // 태스크별로 "가장 최근(attemptNumber 최댓값)" attempt만 봐야 한다 — passed=false 로만 필터링하면
        // 그 뒤에 이미 새 attempt(성공/실패 불문)가 생겼는데도 옛날 실패 attempt가 다시 잡히는 버그가 생김.
        val latestByTask = attempts.groupBy { it[TaskQuizAttemptTable.planTaskId].value }
            .mapValues { (_, taskAttempts) -> taskAttempts.maxBy { it[TaskQuizAttemptTable.attemptNumber] } }

        return latestByTask.mapNotNull { (planTaskId, row) ->
            if (row[TaskQuizAttemptTable.passed] != false) return@mapNotNull null // null(미제출) 또는 true(통과)면 재시도 대상 아님

            val attemptNumber = row[TaskQuizAttemptTable.attemptNumber]
            if (attemptNumber >= MAX_ATTEMPTS) return@mapNotNull null
            val task = tasks.getValue(planTaskId)
            if (task.status == PlanTaskStatus.FAILED) return@mapNotNull null // 재시도 대기 중에 실패 처리했으면 더 만들지 않는다

            val retryAt = row[TaskQuizAttemptTable.createdAt].atZoneSameInstant(ZONE).plusMinutes(RETRY_DELAY_MINUTES)
            if (retryAt.isAfter(zonedNow)) return@mapNotNull null

            val nextAttempt = attemptNumber + 1
            // 할일 이름·상태는 위에서 읽은 것을 그대로 쓴다 — 후보마다 PlanTaskRow 를 다시 조회하지 않는다
            val payload = TaskQuizJobPayload(planTaskId, nextAttempt, task.taskName)
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
