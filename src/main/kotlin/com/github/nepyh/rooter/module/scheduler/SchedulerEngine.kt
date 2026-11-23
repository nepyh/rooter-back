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
import java.util.concurrent.ConcurrentHashMap

/**
 * scheduler 코어 엔진.
 *
 * 매 틱마다 등록된 [SchedulerJob] 들의 파생 규칙을 평가하고,
 * job_runs 에 claim (INSERT ... ON CONFLICT DO NOTHING) 한 뒤 실행한다.
 * 스케줄 상태를 메모리에 보관하지 않으므로 재시작 시 휘발되는 것이 없다.
 *
 * 파생 결과에는 이미 실행을 마친 작업도 계속 섞이므로, claim 전에 job_runs 기록을 한 번에 조회해 걸러낸다
 * ([JobRunRepo.findClaimable]). 걸러내지 않으면 끝난 작업마다 매분 헛 INSERT 가 나간다.
 * 실패(failed)한 작업은 잡이 [SchedulerJob.retryDelays] 를 두었으면 그 간격이 지난 뒤 다시 실행한다 (#318).
 *
 * [SchedulerJob.monitored] 인 잡들의 상태를 모아 틱 간격마다 [monitor](기본 Sentry Crons)에 heartbeat 하나를 보낸다
 * (무료 플랜은 모니터가 1개라 잡마다 보내지 않는다). 모두 정상이면 OK, 한 잡이라도 루프가 죽었거나(OOM 같은 Error)·마지막 틱이
 * 실패했거나·한 틱이 [stallAfter] 넘게 끝나지 않으면 ERROR. 스케줄러가 통째로 멈추면 heartbeat 가 끊겨 누락으로 잡힌다.
 *
 * 틱 안의 예외(Exception)는 job_runs 기록의 DB 오류까지 그 틱의 실패로만 남기고 루프는 계속 돈다 — DB 가 잠깐 끊겨도
 * 서버를 재시작할 때까지 잡이 멈추지 않게 (#317).
 *
 * 잡마다 따로 틱 루프를 돈다. 한 루프에서 차례로 돌리면 앞 잡(예: LLM 으로 퀴즈를 만드는 잡)이 오래 걸리는 동안
 * 뒤 잡(예: 시작 몇 분 전에 보내야 하는 할일 알림)이 밀리고, 틱 시작 때 잰 낡은 now 로 파생까지 하게 된다.
 */
class SchedulerEngine(
    private val jobs: List<SchedulerJob>,
    private val jobRunRepo: JobRunRepo,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL,
    private val monitor: JobMonitor = SentryJobMonitor,
    private val stallAfter: Duration = DEFAULT_STALL_AFTER
) {
    private val logger = LoggerFactory.getLogger(SchedulerEngine::class.java)

    /** 잡 루프 하나의 상태. 잡 루프가 쓰고 heartbeat 루프가 읽는다 */
    private class JobHealth {
        @Volatile var tickStartedAt: Long? = null // 진행 중인 틱의 시작(System.nanoTime), 쉬는 중이면 null
        @Volatile var lastTickOk: Boolean = true
        @Volatile var loopDead: Boolean = false
    }

    private val health = ConcurrentHashMap<String, JobHealth>()

    fun start(scope: CoroutineScope) {
        logger.info("scheduler 엔진 시작: 잡 {} 개, 틱 {} 초", jobs.size, tickInterval.seconds)
        jobs.forEach { job ->
            val state = JobHealth().also { health[job.jobType] = it }
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        state.tickStartedAt = System.nanoTime()
                        try {
                            state.lastTickOk = runJob(job)
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: Exception) {
                            // runJob 이 잡지 않는 job_runs 기록(filterUnclaimed·claim·markDone·markFailed)의 DB 오류.
                            // 연결 끊김·타임아웃처럼 일시적인 경우가 대부분이라 이 틱만 실패로 두고 다음 틱에 다시 돈다 (#317)
                            ErrorMonitoring.capture(cause, ErrorSource.SCHEDULER)
                            logger.error("스케줄러 틱 실패, 다음 틱에 다시 시도합니다: jobType={}", job.jobType, cause)
                            state.lastTickOk = false
                        } finally {
                            state.tickStartedAt = null
                        }
                        delay(tickInterval.toMillis())
                    }
                } catch (cause: CancellationException) {
                    throw cause // 종료(배포·재시작)는 실패가 아니다
                } catch (cause: Throwable) {
                    state.loopDead = true
                    logger.error("스케줄러 잡 루프가 멈췄습니다: jobType={}", job.jobType, cause)
                    throw cause
                }
            }
        }
        if (jobs.any { it.monitored }) {
            scope.launch(Dispatchers.IO) {
                val intervalMinutes = tickInterval.toMinutesCeil()
                while (isActive) {
                    val unhealthy = unhealthyJobs()
                    if (unhealthy.isNotEmpty()) logger.warn("스케줄러 heartbeat ERROR: {}", unhealthy)
                    monitor.heartbeat(unhealthy.isEmpty(), intervalMinutes)
                    delay(tickInterval.toMillis())
                }
            }
        }
    }

    /** heartbeat 를 ERROR 로 만드는 잡과 그 이유. 모니터 대상([SchedulerJob.monitored])만 본다 */
    private fun unhealthyJobs(now: Long = System.nanoTime()): List<String> = jobs.filter { it.monitored }.mapNotNull { job ->
        val state = health[job.jobType] ?: return@mapNotNull null
        val stalled = state.tickStartedAt?.let { now - it > stallAfter.toNanos() } == true
        when {
            state.loopDead -> "${job.jobType}(루프 멈춤)"
            stalled -> "${job.jobType}(틱이 ${stallAfter.toMinutes()}분 넘게 진행 중)"
            !state.lastTickOk -> "${job.jobType}(마지막 틱 실패)"
            else -> null
        }
    }

    /** 모든 잡을 한 번씩 차례로 돌린다 (테스트용 — 운영은 [start] 의 잡별 루프가 [runJob] 을 부른다). */
    suspend fun runTick() {
        jobs.forEach { runJob(it) }
    }

    /**
     * 잡 하나의 한 틱. now 는 이 잡 차례에 잰다 — 다른 잡이 오래 걸려도 낡은 시각으로 파생하지 않게.
     * @return 파생·실행이 모두 성공했으면 true. 하나라도 실패했으면 false (실패는 job_runs·Sentry 이벤트에 남고 틱은 계속된다)
     */
    suspend fun runJob(job: SchedulerJob): Boolean {
        val now = OffsetDateTime.now()
        val dueJobs = try {
            job.findDue(now)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ErrorMonitoring.capture(e, ErrorSource.SCHEDULER)
            logger.error("파생 규칙 평가 실패: jobType={}", job.jobType, e)
            return false
        }
        var ok = true
        // 매 틱 다시 파생되는 후보 중 이미 기록된 runKey 는 claim 까지 보내지 않는다. 실패한 작업은 잡의 재시도 간격이 지났으면 다시 고른다
        jobRunRepo.findClaimable(job.jobType, dueJobs, job.retryDelays, now).forEach { (due, retryCount) ->
            val claimed = if (retryCount == null) {
                jobRunRepo.claim(job.jobType, due)
            } else {
                jobRunRepo.claimRetry(job.jobType, due.runKey, retryCount)
            }
            if (!claimed) return@forEach

            val failure = try {
                job.execute(due)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            if (failure == null) {
                // 실행 뒤 완료 기록이 실패해도 failed 로 바꾸지 않는다 — 재시도 대상이 되면 이미 끝난 일(보충 할일 등)을 한 번 더 한다.
                // 이 예외는 틱의 실패로 올라가고, 행은 pending 으로 남아 다시 실행되지 않는다
                jobRunRepo.markDone(job.jobType, due.runKey)
            } else {
                ErrorMonitoring.capture(failure, ErrorSource.SCHEDULER)
                logger.error("잡 실행 실패: jobType={} runKey={} retryCount={}", job.jobType, due.runKey, retryCount ?: 0, failure)
                jobRunRepo.markFailed(job.jobType, due.runKey, failure)
                ok = false
            }
        }
        return ok
    }

    private fun Duration.toMinutesCeil(): Int = ((toMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)

    companion object {
        val DEFAULT_TICK_INTERVAL: Duration = Duration.ofSeconds(60)

        /** 한 틱이 이보다 오래 끝나지 않으면(LLM 호출이 멈춤 등) heartbeat 를 ERROR 로 보낸다 */
        val DEFAULT_STALL_AFTER: Duration = Duration.ofMinutes(30)
    }
}
