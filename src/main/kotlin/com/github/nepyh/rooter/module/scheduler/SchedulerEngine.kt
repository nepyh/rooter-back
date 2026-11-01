package com.github.nepyh.rooter.module.scheduler

import com.github.nepyh.rooter.common.monitoring.ErrorMonitoring
import com.github.nepyh.rooter.common.monitoring.ErrorSource
import kotlinx.coroutines.CancellationException
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
 *
 * 파생 결과에는 이미 실행을 마친 작업도 계속 섞이므로, claim 전에 job_runs 기록을 한 번에 조회해 걸러낸다
 * ([JobRunRepo.filterUnclaimed]). 걸러내지 않으면 끝난 작업마다 매분 헛 INSERT 가 나간다.
 *
 * 잡마다 따로 틱 루프를 돈다. 한 루프에서 차례로 돌리면 앞 잡(예: LLM 으로 퀴즈를 만드는 잡)이 오래 걸리는 동안
 * 뒤 잡(예: 시작 몇 분 전에 보내야 하는 할일 알림)이 밀리고, 틱 시작 때 잰 낡은 now 로 파생까지 하게 된다.
 */
class SchedulerEngine(
    private val jobs: List<SchedulerJob>,
    private val jobRunRepo: JobRunRepo,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL
) {
    private val logger = LoggerFactory.getLogger(SchedulerEngine::class.java)

    fun start(scope: CoroutineScope) {
        logger.info("scheduler 엔진 시작: 잡 {} 개, 틱 {} 초", jobs.size, tickInterval.seconds)
        jobs.forEach { job ->
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        runJob(job)
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        ErrorMonitoring.capture(cause, ErrorSource.SCHEDULER)
                        throw cause
                    }
                    delay(tickInterval.toMillis())
                }
            }
        }
    }

    /** 모든 잡을 한 번씩 차례로 돌린다 (테스트용 — 운영은 [start] 의 잡별 루프가 [runJob] 을 부른다). */
    suspend fun runTick() {
        jobs.forEach { runJob(it) }
    }

    /** 잡 하나의 한 틱. now 는 이 잡 차례에 잰다 — 다른 잡이 오래 걸려도 낡은 시각으로 파생하지 않게. */
    suspend fun runJob(job: SchedulerJob) {
        val now = OffsetDateTime.now()
        val dueJobs = try {
            job.findDue(now)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ErrorMonitoring.capture(e, ErrorSource.SCHEDULER)
            logger.error("파생 규칙 평가 실패: jobType={}", job.jobType, e)
            emptyList()
        }
        // 매 틱 다시 파생되는 후보 중 job_runs 에 이미 기록된 runKey 는 claim(=INSERT) 까지 보내지 않는다
        jobRunRepo.filterUnclaimed(job.jobType, dueJobs).forEach { due ->
            val claimed = jobRunRepo.claim(job.jobType, due)
            if (!claimed) return@forEach

            try {
                job.execute(due)
                jobRunRepo.markDone(job.jobType, due.runKey)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ErrorMonitoring.capture(e, ErrorSource.SCHEDULER)
                logger.error("잡 실행 실패: jobType={} runKey={}", job.jobType, due.runKey, e)
                jobRunRepo.markFailed(job.jobType, due.runKey, e)
            }
        }
    }

    companion object {
        val DEFAULT_TICK_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
