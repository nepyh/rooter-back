package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.user.model.UserRow
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60

/**
 * 사용자 수면시간 (취침·기상 시각). 설정하지 않은 사용자는 [DEFAULT] (23:00 취침, 06:30 기상).
 * 계획 생성·바쁜 시간 조회·챗봇 재조정/미루기·복습 배치가 이 시간을 피해서 할일을 잡는다 ([PlanTaskScheduler]).
 */
data class SleepTime(val sleepAt: LocalTime, val wakeAt: LocalTime) {

    /**
     * 하루 안에서 막을 수면 구간 (분).
     * - 자정 전에 자면 (23:00 취침, 06:30 기상): 00:00~기상, 취침~24:00 두 구간
     * - 자정 넘어 자면 (01:00 취침, 07:00 기상): 취침~기상 한 구간 (전날 밤 23:00~24:00 은 비어 있음)
     */
    fun ranges(): List<Pair<Int, Int>> {
        val sleep = PlanTaskScheduler.toMinutes(sleepAt)
        val wake = PlanTaskScheduler.toMinutes(wakeAt)
        val ranges = if (sleep > wake) listOf(0 to wake, sleep to DAY_MINUTES) else listOf(sleep to wake)
        return ranges.filter { (start, end) -> start < end } // 00:00 기상이면 앞 구간이 비어 있다
    }

    companion object {
        val DEFAULT = SleepTime(LocalTime.of(23, 0), LocalTime.of(6, 30))
    }
}

/** 사용자가 저장한 수면시간. 저장하지 않았으면 [SleepTime.DEFAULT]. 트랜잭션 안에서 부른다. */
fun userSleepTime(userId: Int): SleepTime {
    val user = UserRow.findById(userId) ?: return SleepTime.DEFAULT
    val sleepAt = user.sleepTime ?: return SleepTime.DEFAULT
    val wakeAt = user.wakeTime ?: return SleepTime.DEFAULT
    return SleepTime(sleepAt, wakeAt)
}
