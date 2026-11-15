package com.github.nepyh.rooter.common

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 서비스 기준 시간대. 사용자가 전부 한국에 있으므로 "오늘" 은 항상 이 시간대로 계산한다. */
val APP_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 서비스 기준(한국) 오늘 날짜. 서버 JVM 시간대와 무관하다.
 * 운영 서버는 UTC 라서 `LocalDate.now()` 를 쓰면 한국 시간 00:00~09:00 에 어제 날짜가 나온다.
 */
fun todayInAppZone(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(APP_ZONE))

/**
 * [date] 의 [time] (서비스 기준 시간대) 이 지났는지. 정각이면 지난 것으로 본다.
 * 할일은 계획 날짜 + 시작 시간이 되기 전에는 퀴즈로 완료하거나 실패 처리할 수 없다 (#353).
 */
fun isReachedInAppZone(date: LocalDate, time: LocalTime, clock: Clock = Clock.systemUTC()): Boolean =
    !LocalDateTime.now(clock.withZone(APP_ZONE)).isBefore(date.atTime(time))
