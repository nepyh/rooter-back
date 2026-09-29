package com.github.nepyh.rooter.common

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 서비스 기준 시간대. 사용자가 전부 한국에 있으므로 "오늘" 은 항상 이 시간대로 계산한다. */
val APP_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 서비스 기준(한국) 오늘 날짜. 서버 JVM 시간대와 무관하다.
 * 운영 서버는 UTC 라서 `LocalDate.now()` 를 쓰면 한국 시간 00:00~09:00 에 어제 날짜가 나온다.
 */
fun todayInAppZone(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(APP_ZONE))
