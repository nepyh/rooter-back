package com.github.nepyh.rooter.module.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class NotificationScheduler(private val notificationService: NotificationService) {

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
    }

    private val logger = LoggerFactory.getLogger(NotificationScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { notificationService.sendDueReminders() }
                    .onFailure { logger.error("알림 발송 중 오류 발생", it) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
}
