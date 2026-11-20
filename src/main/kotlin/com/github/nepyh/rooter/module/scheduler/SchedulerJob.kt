package com.github.nepyh.rooter.module.scheduler

import java.time.Duration
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

    /**
     * 스케줄러 heartbeat(Sentry Crons)에 이 잡의 상태를 넣을지. true 면 이 잡의 루프가 죽거나, 마지막 틱이 실패하거나,
     * 한 틱이 오래 멈추면 heartbeat 가 ERROR 가 된다. 예제 잡처럼 운영에 의미 없는 잡은 false.
     */
    val monitored: Boolean get() = false

    /**
     * 실행이 실패(job_runs.status = failed)했을 때 다시 시도하기까지의 간격. 크기가 곧 최대 재시도 횟수다 (#318).
     * n 번째 재시도는 직전 실패 시각(finished_at)에서 `retryDelays[n - 1]` 이 지난 뒤, [findDue] 가 같은 runKey 를
     * 아직 내고 있을 때만 일어난다 — 후보에서 빠진 작업(지난 날짜·이미 지난 알림)은 재시도하지 않는다.
     * 기본은 재시도하지 않음. 다시 실행해도 결과가 겹치지 않는(멱등) 잡만 켠다.
     */
    val retryDelays: List<Duration> get() = emptyList()

    /** 지금 실행해야 할 작업 지시 목록을 도메인 데이터에서 파생한다. */
    fun findDue(now: OffsetDateTime): List<DueJob>

    /** [DueJob] 실제 처리 (도메인 로직) */
    suspend fun execute(due: DueJob)
}

/** LLM 장애처럼 잠깐 뒤 다시 하면 될 실패에 쓰는 기본 재시도 간격: 5분 · 15분 · 30분 뒤, 최대 3회 (#318) */
val DEFAULT_RETRY_DELAYS: List<Duration> = listOf(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30))
