package com.github.nepyh.rooter.module.scheduler

import java.time.OffsetDateTime

/**
 * scheduler 코어가 실행할 작업 지시.
 *
 * @param runKey (job_type, runKey) 조합으로 유니크해야 하며, 같은 runKey 는 중복 실행되지 않는다.
 *              예: "user_123_2026-08-08"
 * @param scheduledAt 예정 실행 시각 (파생 결과)
 * @param payload 핸들러가 사용할 추가 데이터 (JSON 문자열)
 */
data class DueJob(
    val runKey: String,
    val scheduledAt: OffsetDateTime,
    val payload: String = "{}"
)

/**
 * 도메인 모듈이 scheduler 코어에 등록하는 잡.
 * scheduler 코어는 "언제/누구를"만 알고, 실제 실행 로직([execute])은 도메인 모듈에 있다.
 */
interface SchedulerJob {
    val jobType: String

    /** 지금 실행해야 할 작업 지시 목록을 도메인 데이터에서 파생한다. */
    fun findDue(now: OffsetDateTime): List<DueJob>

    /** [DueJob] 실제 처리 (도메인 로직) */
    suspend fun execute(due: DueJob)
}
