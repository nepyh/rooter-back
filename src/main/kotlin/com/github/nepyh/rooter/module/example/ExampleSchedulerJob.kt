package com.github.nepyh.rooter.module.example

import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.scheduler.model.SchedulePeriod
import com.github.nepyh.rooter.module.scheduler.model.ScheduleSpec
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * scheduler 코어 사용 예시를 보여주는 데모 잡 (dev 전용).
 * 매일 09:00 (KST) 에 로그 한 줄을 남긴다.
 */
class ExampleSchedulerJob : SchedulerJob {
    private val logger = LoggerFactory.getLogger(ExampleSchedulerJob::class.java)

    override val jobType: String = "example_log"

    private val spec = ScheduleSpec(
        period = SchedulePeriod.DAILY,
        timeOfDay = LocalTime.of(9, 0)
    )

    override fun findDue(now: OffsetDateTime): List<DueJob> {
        if (!spec.matches(now)) return emptyList()
        val targetDate = now.atZoneSameInstant(spec.timezone).toLocalDate()
        return listOf(
            DueJob(
                runKey = "example_log_$targetDate",
                scheduledAt = now
            )
        )
    }

    override suspend fun execute(due: DueJob) {
        logger.info("예시 스케줄러 잡 실행됨: {}", due.runKey)
    }
}
