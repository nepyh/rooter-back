package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.monitoring.ErrorMonitoring
import com.github.nepyh.rooter.common.monitoring.ErrorSource
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanGenerationRequestTable
import com.github.nepyh.rooter.module.planboard.model.PlanGenerationStatus
import com.github.nepyh.rooter.module.scheduler.DEFAULT_RETRY_DELAYS
import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.JobRunRepo
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
private data class PlanGenerationPayload(val planBoardId: Int, val chunkStart: String)

/**
 * 생성 중(generating)인 AI 계획의 다음 구간을 하나씩 만드는 잡 (#405).
 *
 * - 후보: 생성 중인 보드마다 다음 구간 하나. run_key 에 구간 시작일을 넣어 같은 구간은 한 번만 만든다 (job_runs claim)
 * - 보통은 응답 직후 [PlanGenerationContinuer] 가 바로 이어 만들고, 이 잡은 서버 재시작·실패 재시도를 맡는다
 * - AI 실패는 5·15·30분 뒤 다시 한다. 재시도까지 다 실패하면 보드를 failed 로 바꾸고 알린다
 */
class PlanGenerationContinueJob(
    private val service: PlanGenerationService
) : SchedulerJob {
    override val jobType: String = PLAN_GENERATION_JOB_TYPE
    override val monitored: Boolean = true
    override val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS

    private val json = Json { ignoreUnknownKeys = true }

    override fun findDue(now: OffsetDateTime): List<DueJob> = transaction {
        (PlanBoardTable innerJoin PlanGenerationRequestTable)
            .select(PlanBoardTable.id, PlanGenerationRequestTable.generatedUntil)
            .where { PlanBoardTable.generationStatus eq PlanGenerationStatus.GENERATING.value }
            .map { dueOf(it[PlanBoardTable.id].value, it[PlanGenerationRequestTable.generatedUntil], now) }
    }

    /** 이 보드의 다음 구간 작업 지시. 생성 중이 아니면 null */
    fun dueFor(boardId: Int, now: OffsetDateTime = OffsetDateTime.now()): DueJob? = transaction {
        (PlanBoardTable innerJoin PlanGenerationRequestTable)
            .select(PlanGenerationRequestTable.generatedUntil)
            .where { (PlanBoardTable.id eq boardId) and (PlanBoardTable.generationStatus eq PlanGenerationStatus.GENERATING.value) }
            .firstOrNull()
            ?.let { dueOf(boardId, it[PlanGenerationRequestTable.generatedUntil], now) }
    }

    private fun dueOf(boardId: Int, generatedUntil: LocalDate, now: OffsetDateTime): DueJob {
        val chunkStart = service.nextChunkStart(generatedUntil)
        return DueJob(
            runKey = planGenerationRunKey(boardId, chunkStart),
            scheduledAt = now,
            payload = json.encodeToString(PlanGenerationPayload.serializer(), PlanGenerationPayload(boardId, chunkStart.toString()))
        )
    }

    override suspend fun execute(due: DueJob) {
        val payload = json.decodeFromString(PlanGenerationPayload.serializer(), due.payload)
        try {
            service.continueGeneration(payload.planBoardId, LocalDate.parse(payload.chunkStart))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 이번이 마지막 재시도였으면 실패로 확정한다 (스케줄러는 retry_count 가 retryDelays 크기에 닿으면 더 잡지 않는다)
            if (retryCountOf(due.runKey) >= retryDelays.size) service.markFailed(payload.planBoardId, e)
            throw e
        }
    }

    private fun retryCountOf(runKey: String): Int = transaction {
        JobRunTable.select(JobRunTable.retryCount)
            .where { (JobRunTable.jobType eq jobType) and (JobRunTable.runKey eq runKey) }
            .firstOrNull()
            ?.get(JobRunTable.retryCount)
            ?: 0
    }
}

/**
 * 앞 구간을 응답한 직후 남은 구간을 바로 이어서 만든다 (#405). 스케줄러 틱(1분)을 기다리면 30일 계획을 다 만드는 데 몇 분이 걸린다.
 * 스케줄러 잡과 같은 run_key 로 job_runs 에 claim 하므로 둘이 같은 구간을 함께 만들지 않는다.
 * 실패하면 실행 기록만 failed 로 남기고 멈춘다 — 재시도는 스케줄러 잡이 [SchedulerJob.retryDelays] 대로 한다.
 */
class PlanGenerationContinuer(
    private val job: PlanGenerationContinueJob,
    private val jobRunRepo: JobRunRepo,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(PlanGenerationContinuer::class.java)

    fun kick(boardId: Int) {
        scope.launch { runFor(boardId) }
    }

    /** 이 보드의 남은 구간을 차례로 만든다. 다른 곳이 이미 잡았거나 실패하면 멈춘다 */
    suspend fun runFor(boardId: Int) {
        while (true) {
            val due = job.dueFor(boardId) ?: return
            if (!jobRunRepo.claim(job.jobType, due)) return
            try {
                job.execute(due)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ErrorMonitoring.capture(e, ErrorSource.SCHEDULER)
                logger.error("AI 계획 이어 만들기 실패 (스케줄러가 재시도): boardId={} runKey={}", boardId, due.runKey, e)
                jobRunRepo.markFailed(job.jobType, due.runKey, e)
                return
            }
            jobRunRepo.markDone(job.jobType, due.runKey)
        }
    }
}
