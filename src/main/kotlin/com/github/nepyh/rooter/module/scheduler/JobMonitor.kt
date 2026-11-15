package com.github.nepyh.rooter.module.scheduler

import com.github.nepyh.rooter.common.monitoring.ErrorMonitoring
import java.time.Duration

/**
 * 잡 한 틱의 시작·끝을 외부 모니터에 알린다. [start] 가 돌려준 함수를 틱이 끝날 때 성공 여부로 부른다.
 * 테스트에서는 바꿔 끼워 check-in 순서만 본다.
 */
fun interface JobMonitor {
    fun start(monitorSlug: String, intervalMinutes: Int): (ok: Boolean) -> Unit
}

/** Sentry Crons. DSN 이 없으면 [ErrorMonitoring] 이 아무것도 보내지 않는다 */
object SentryJobMonitor : JobMonitor {
    override fun start(monitorSlug: String, intervalMinutes: Int): (Boolean) -> Unit {
        val checkInId = ErrorMonitoring.checkInStarted(monitorSlug, intervalMinutes)
        val startedAt = System.nanoTime()
        return { ok -> ErrorMonitoring.checkInFinished(monitorSlug, checkInId, ok, Duration.ofNanos(System.nanoTime() - startedAt)) }
    }
}
