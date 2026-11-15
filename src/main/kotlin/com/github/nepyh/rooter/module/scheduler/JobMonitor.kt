package com.github.nepyh.rooter.module.scheduler

import com.github.nepyh.rooter.common.monitoring.ErrorMonitoring

/**
 * 스케줄러 전체의 상태를 외부 모니터에 알린다. [SchedulerEngine] 이 틱 간격마다 한 번 부른다.
 * 테스트에서는 바꿔 끼워 보낸 상태만 본다.
 */
fun interface JobMonitor {
    fun heartbeat(ok: Boolean, intervalMinutes: Int)
}

/** Sentry Crons 모니터 이름. 무료 플랜은 모니터가 1개라 스케줄러 전체로 하나만 쓴다 */
const val SCHEDULER_MONITOR_SLUG = "scheduler"

/** Sentry Crons. DSN 이 없으면 [ErrorMonitoring] 이 아무것도 보내지 않는다 */
object SentryJobMonitor : JobMonitor {
    override fun heartbeat(ok: Boolean, intervalMinutes: Int) =
        ErrorMonitoring.checkIn(SCHEDULER_MONITOR_SLUG, ok, intervalMinutes)
}
