package com.github.nepyh.rooter.module.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

/**
 * scheduler 코어 엔진.
 *
 * 매 틱마다 등록된 [SchedulerJob] 들의 파생 규칙을 평가하고,
 * job_runs 에 claim (INSERT ... ON CONFLICT DO NOTHING) 한 뒤 실행한다.
 * 스케줄 상태를 메모리에 보관하지 않으므로 재시작 시 휘발되는 것이 없다.
 */
class SchedulerEngine(
    private val jobs: List<SchedulerJob>,
    private val jobRunRepo: JobRunRepo,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL
) {
    private val logger = LoggerFactory.getLogger(SchedulerEngine::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            logger.info("scheduler 엔진 시작: 잡 {} 개, 틱 {} 초", jobs.size, tickInterval.seconds)
            while (isActive) {
                runTick()
                delay(tickInterval.toMillis())
            }
        }
    }

    suspend fun runTick() {
        val now = OffsetDateTime.now()
        jobs.forEach { job ->
            val dueJobs = try {
                job.findDue(now)
            } catch (e: Exception) {
                logger.error("파생 규칙 평가 실패: jobType={}", job.jobType, e)
                emptyList()
            }
            dueJobs.forEach { due ->
                val claimed = jobRunRepo.claim(job.jobType, due)
                if (!claimed) return@forEach

                try {
                    job.execute(due)
                    jobRunRepo.markDone(job.jobType, due.runKey)
                } catch (e: Exception) {
                    logger.error("잡 실행 실패: jobType={} runKey={}", job.jobType, due.runKey, e)
                    jobRunRepo.markFailed(job.jobType, due.runKey, e)
                }
            }
        }
    }

    companion object {
        val DEFAULT_TICK_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
