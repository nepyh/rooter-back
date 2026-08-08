package com.github.nepyh.rooter

import com.github.nepyh.rooter.module.scheduler.model.SchedulePeriod
import com.github.nepyh.rooter.module.scheduler.model.ScheduleSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ScheduleSpecTest : StringSpec({

    val utc = ZoneOffset.UTC

    "DAILY 주기는 날짜와 무관하게 timeOfDay 시각(분)에만 매칭된다" {
        val spec = ScheduleSpec(period = SchedulePeriod.DAILY, timeOfDay = LocalTime.of(22, 0), timezone = utc)

        spec.matches(OffsetDateTime.of(2026, 8, 7, 22, 0, 30, 0, utc)) shouldBe true
        spec.matches(OffsetDateTime.of(2026, 8, 8, 22, 0, 0, 0, utc)) shouldBe true
        spec.matches(OffsetDateTime.of(2026, 8, 8, 22, 1, 0, 0, utc)) shouldBe false
        spec.matches(OffsetDateTime.of(2026, 8, 8, 9, 0, 0, 0, utc)) shouldBe false
    }

    "WEEKLY 주기는 요일 + 시각이 모두 맞아야 매칭된다" {
        val spec = ScheduleSpec(
            period = SchedulePeriod.WEEKLY,
            timeOfDay = LocalTime.of(20, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            timezone = utc
        )

        // 2026-08-03 = 월요일
        spec.matches(OffsetDateTime.of(2026, 8, 3, 20, 0, 0, 0, utc)) shouldBe true
        // 2026-08-04 = 화요일
        spec.matches(OffsetDateTime.of(2026, 8, 4, 20, 0, 0, 0, utc)) shouldBe false
        // 월요일이지만 시각이 다름
        spec.matches(OffsetDateTime.of(2026, 8, 3, 21, 0, 0, 0, utc)) shouldBe false
    }

    "MONTHLY 주기는 일(dayOfMonth) + 시각이 모두 맞아야 매칭된다" {
        val spec = ScheduleSpec(
            period = SchedulePeriod.MONTHLY,
            timeOfDay = LocalTime.of(9, 0),
            dayOfMonth = 1,
            timezone = utc
        )

        spec.matches(OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, utc)) shouldBe true
        spec.matches(OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, utc)) shouldBe false
    }

    "offsetDays 만큼 period 판정 기준 날짜가 이동한다" {
        // 월요일 기준 +1일 이동이면, 일요일(today) + 1 = 월요일이므로 일요일에 매칭된다
        val spec = ScheduleSpec(
            period = SchedulePeriod.WEEKLY,
            timeOfDay = LocalTime.of(20, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            offsetDays = 1,
            timezone = utc
        )

        // 2026-08-09 = 일요일
        spec.matches(OffsetDateTime.of(2026, 8, 9, 20, 0, 0, 0, utc)) shouldBe true
        // 2026-08-10 = 월요일 (기준일 +1 = 화요일)
        spec.matches(OffsetDateTime.of(2026, 8, 10, 20, 0, 0, 0, utc)) shouldBe false
    }

    "WEEKLY 주기에 dayOfWeek 가 없으면 예외가 발생한다" {
        shouldThrow<IllegalArgumentException> {
            ScheduleSpec(period = SchedulePeriod.WEEKLY, timeOfDay = LocalTime.of(20, 0))
        }
    }

    "MONTHLY 주기에 dayOfMonth 가 없으면 예외가 발생한다" {
        shouldThrow<IllegalArgumentException> {
            ScheduleSpec(period = SchedulePeriod.MONTHLY, timeOfDay = LocalTime.of(9, 0))
        }
    }

    "dayOfMonth 는 1~31 범위를 벗어나면 예외가 발생한다" {
        shouldThrow<IllegalArgumentException> {
            ScheduleSpec(period = SchedulePeriod.MONTHLY, timeOfDay = LocalTime.of(9, 0), dayOfMonth = 32)
        }
    }
})
