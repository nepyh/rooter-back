package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.user.model.UnavailableKind
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.eq

private const val DAY_MINUTES = 24 * 60
private const val BREAKFAST_MINUTES = 30 // 기본 아침: 기상 후 30분
private val DEFAULT_LUNCH = 12 * 60 to 13 * 60 // 12:00~13:00
private val DEFAULT_DINNER = 18 * 60 to 19 * 60 // 18:00~19:00

/** 사용자가 등록한 불가능 시간 하나 (요일 1=월~7=일, 분) */
data class RoutineSlot(val dayOfWeek: Int, val kind: UnavailableKind, val start: Int, val end: Int)

/**
 * 할일을 둘 수 없는 사용자별 생활 시간 — 수면, 하교 후 귀가, 식사, 학원·기타 일정.
 * 계획 생성·바쁜 시간 조회·챗봇 재조정/미루기/편집·복습 배치가 [PlanTaskScheduler.buildTypedUnavailableRanges] 로 이 시간을 피한다.
 *
 * 식사는 끼니마다 그 요일에 등록한 것이 있으면 그것을, 없으면 기본값을 쓴다
 * (아침 = 기상 후 30분, 점심 12:00~13:00, 저녁 18:00~19:00). 등교일의 아침·점심은 학교 시간에 이미 막혀 있다.
 *
 * @property commuteMinutes 등교일에 하교 시각부터 할일을 잡지 않을 시간 (분)
 */
data class UserRoutine(
    val sleepTime: SleepTime = SleepTime.DEFAULT,
    val commuteMinutes: Int = DEFAULT_COMMUTE_MINUTES,
    val slots: List<RoutineSlot> = emptyList()
) {

    /** 그 요일에 막을 식사(MEAL)와 학원·기타 일정(UNAVAILABLE) 구간 */
    fun ranges(dayOfWeek: Int): List<TypedRange> {
        val daySlots = slots.filter { it.dayOfWeek == dayOfWeek }
        val meals = listOf(UnavailableKind.BREAKFAST, UnavailableKind.LUNCH, UnavailableKind.DINNER).flatMap { meal ->
            daySlots.filter { it.kind == meal }.map { it.start to it.end }.ifEmpty { listOf(defaultMeal(meal)) }
        }
        return meals.map { (start, end) -> TypedRange(UnavailableType.MEAL, start, end) } +
            daySlots.filterNot { it.kind.isMeal }.map { TypedRange(UnavailableType.UNAVAILABLE, it.start, it.end) }
    }

    private fun defaultMeal(meal: UnavailableKind): Pair<Int, Int> = when (meal) {
        UnavailableKind.BREAKFAST -> {
            val wake = PlanTaskScheduler.toMinutes(sleepTime.wakeAt)
            wake to minOf(wake + BREAKFAST_MINUTES, DAY_MINUTES)
        }
        UnavailableKind.LUNCH -> DEFAULT_LUNCH
        else -> DEFAULT_DINNER
    }

    companion object {
        const val DEFAULT_COMMUTE_MINUTES = 40
        val DEFAULT = UserRoutine()
    }
}

/** 사용자의 수면시간·귀가 시간·등록한 불가능 시간. 저장하지 않은 값은 기본값. 트랜잭션 안에서 부른다. */
fun userRoutine(userId: Int): UserRoutine {
    val slots = UnavailableTimeRow.find { UnavailableTimeTable.user eq userId }.map {
        RoutineSlot(it.dayOfWeek.code.toInt(), it.kind, PlanTaskScheduler.toMinutes(it.startTime), PlanTaskScheduler.toMinutes(it.endTime))
    }
    val commuteMinutes = UserRow.findById(userId)?.commuteMinutes?.toInt() ?: UserRoutine.DEFAULT_COMMUTE_MINUTES
    return UserRoutine(sleepTime = userSleepTime(userId), commuteMinutes = commuteMinutes, slots = slots)
}
