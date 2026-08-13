package com.github.nepyh.rooter.module.scheduler.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class SchedulePeriod {
    DAILY, WEEKLY, MONTHLY
}

/**
 * cron 의 기본 주기 (일/주/월) 를 표현하는 스케줄 스펙.
 *
 * @param timeOfDay 매일/매주/매월 "몇 시"에 실행할지
 * @param dayOfWeek WEEKLY 주기일 때 실행 요일
 * @param dayOfMonth MONTHLY 주기일 때 실행 일 (1~31)
 * @param timezone 스케줄 정의 기준 시간대 (기본 Asia/Seoul)
 * @param offsetDays period 판정 기준 날짜를 오늘로부터 offsetDays 일 뒤로 이동 (예: 시험 3일 전 알림 = 3)
 */
data class ScheduleSpec(
    val period: SchedulePeriod,
    val timeOfDay: LocalTime,
    val dayOfWeek: DayOfWeek? = null,
    val dayOfMonth: Int? = null,
    val timezone: ZoneId = DEFAULT_TIMEZONE,
    val offsetDays: Long = 0
) {
    init {
        require(period != SchedulePeriod.WEEKLY || dayOfWeek != null) { "WEEKLY 주기는 dayOfWeek 가 필요합니다." }
        require(period != SchedulePeriod.MONTHLY || dayOfMonth != null) { "MONTHLY 주기는 dayOfMonth 가 필요합니다." }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "dayOfMonth 는 1~31 사이여야 합니다." }
    }

    /**
     * [now] 시각이 이 스펙의 실행 조건 (주기 + 시각) 을 만족하는지 판정한다.
     * 분 단위 정밀도로 비교하므로 60초 틱에서도 안정적으로 매칭된다.
     */
    fun matches(now: OffsetDateTime): Boolean {
        val zoned = now.atZoneSameInstant(timezone)
        val targetDate = zoned.toLocalDate().plusDays(offsetDays)
        val time = zoned.toLocalTime().truncatedTo(ChronoUnit.MINUTES)

        val dayMatches = when (period) {
            SchedulePeriod.DAILY -> true
            SchedulePeriod.WEEKLY -> targetDate.dayOfWeek == dayOfWeek
            SchedulePeriod.MONTHLY -> targetDate.dayOfMonth == dayOfMonth
        }
        return dayMatches && time.hour == timeOfDay.hour && time.minute == timeOfDay.minute
    }

    companion object {
        val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
