package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.studystyle.BestTime
import com.github.nepyh.rooter.module.studystyle.Distraction
import com.github.nepyh.rooter.module.studystyle.StudyStyleProfile
import com.github.nepyh.rooter.module.studystyle.VolatileTime

private const val DAY_MINUTES = 24 * 60
private const val AFTER_SCHOOL_END_MINUTES = 18 * 60 + 30 // "방과 후 ~ 저녁 식사 전" 의 끝 (18:30)
private const val EVENING_START_MINUTES = 19 * 60 + 30 // "저녁 식사 후 ~ 밤" 의 시작 (19:30)
private const val SLEEPY_WINDOW_MINUTES = 60 // 졸음이 방해 요소면 잠들기 전 이만큼은 피한다
private const val VOLATILE_WEEKEND_LIGHTER_MINUTES = 30 // 주말이 변수면 주말 권장을 평일 권장보다 이만큼 낮춘다
private const val RAMP_RANGE_MINUTES = 15 // 첫 며칠 권장 상한을 낮출 때 하한은 상한보다 이만큼 아래

/**
 * 공부 스타일 설문으로 그날 할일을 몇 시에 둘지 정한다 ([PlanTaskScheduler.placeTasks]).
 * - Q1 집중 시간 → 태스크 사이 쉬는 시간 (5/10/15분)
 * - Q2 밤 → 잠들기 전 쪽부터 채움 (아침·오후는 원래대로 가장 이른 빈 시간부터)
 * - Q4 방과 후 → 등교일의 하교~18:30, 저녁 → 19:30~취침 을 다른 빈 시간이 충분하면 피함 (주말은 프롬프트로 분량 조절)
 * - Q7 졸음 → 취침 전 1시간을 피함. 단 Q2 에서 밤을 골랐으면 그 선택을 따른다
 * 설문을 안 했으면 [PlacementOptions.DEFAULT] 와 같다.
 *
 * @param isSchoolDay 등교일 (자정~하교 시각이 막힌 날). 하교 전은 이미 막혀 있어 0~18:30 을 피하면 하교~18:30 을 피하는 것과 같다
 */
fun StudyStyleProfile.placementOptions(isSchoolDay: Boolean, sleepTime: SleepTime): PlacementOptions {
    val bedtime = PlanTaskScheduler.toMinutes(sleepTime.sleepAt)
    val avoidRanges = buildList {
        when (volatileTime) {
            VolatileTime.AFTER_SCHOOL -> if (isSchoolDay) add(0 to AFTER_SCHOOL_END_MINUTES)
            VolatileTime.EVENING -> add(EVENING_START_MINUTES to if (bedtime > EVENING_START_MINUTES) bedtime else DAY_MINUTES)
            VolatileTime.WEEKEND, null -> Unit
        }
        if (distraction == Distraction.SLEEPY && bestTime != BestTime.NIGHT) {
            addAll(beforeBedtime(bedtime, SLEEPY_WINDOW_MINUTES))
        }
    }
    return PlacementOptions(
        breakMinutes = focus?.breakMinutes ?: PlacementOptions.DEFAULT.breakMinutes,
        fromLate = bestTime == BestTime.NIGHT,
        avoidRanges = avoidRanges
    )
}

// 취침 전 [minutes] 분. 자정 직후에 자면 (00:30 취침) 전날 밤 23:30~24:00 과 00:00~00:30 두 구간이 된다
private fun beforeBedtime(bedtime: Int, minutes: Int): List<Pair<Int, Int>> =
    if (bedtime >= minutes) {
        listOf(bedtime - minutes to bedtime)
    } else {
        listOf(0 to bedtime, DAY_MINUTES - (minutes - bedtime) to DAY_MINUTES).filter { (start, end) -> start < end }
    }

/**
 * 공부 스타일 설문으로 그날 하루 권장 공부량(분)을 조정한다 ([PlanGenerationService] 가 AI 에 날짜별로 알려주는 "권장").
 * 프롬프트 지시만으로는 AI 가 서버가 준 권장 범위를 따라가 버려서, 분량에 관한 설문은 권장 범위 자체에 반영한다.
 * - Q4 주말 → 토·일요일 권장을 평일 권장보다 30분 낮춘다 (평소엔 주말 권장이 평일보다 크다)
 * - Q5 평소 공부량이 적음 → 계획 첫 며칠은 권장 상한을 [com.github.nepyh.rooter.module.studystyle.DailyStudyHabit.rampMinutes] 로 낮춘다
 *
 * @param target 그날 기본 권장 (등교일·쉬는 날, 벼락치기 배율까지 반영한 값)
 * @param weekdayTarget 같은 배율의 평일(등교일) 권장
 * @param planDay 전체 계획 기준 몇째 날인지 (1부터). 구간을 나눠 생성해도 첫 며칠은 계획 전체의 첫 며칠만이다
 */
fun StudyStyleProfile.dailyTargetMinutes(target: IntRange, weekdayTarget: IntRange, planDay: Int, isWeekend: Boolean): IntRange {
    var adjusted = target
    if (volatileTime == VolatileTime.WEEKEND && isWeekend) {
        adjusted = (weekdayTarget.first - VOLATILE_WEEKEND_LIGHTER_MINUTES)..(weekdayTarget.last - VOLATILE_WEEKEND_LIGHTER_MINUTES)
    }
    dailyStudy?.rampMinutes?.getOrNull(planDay - 1)?.let { cap ->
        adjusted = minOf(adjusted.first, cap - RAMP_RANGE_MINUTES)..minOf(adjusted.last, cap)
    }
    return adjusted
}
