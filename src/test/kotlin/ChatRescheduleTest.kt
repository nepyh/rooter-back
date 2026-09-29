import com.github.nepyh.rooter.module.chat.ExistingPendingTask
import com.github.nepyh.rooter.module.chat.rescheduleKeepingUnaffected
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ChatRescheduleTest : StringSpec({

    fun m(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val sleep = listOf(0 to m("06:30"), m("23:00") to 24 * 60)

    // 토요일(학교 없음) 16:10~18:30 에 태스크 3개
    val existing = listOf(
        ExistingPendingTask(1, "개념 정리", m("16:10"), m("16:50")),
        ExistingPendingTask(2, "암기", m("17:00"), m("17:50")),
        ExistingPendingTask(3, "문제 풀이", m("18:00"), m("18:30"))
    )
    val same = listOf("개념 정리" to 40, "암기" to 50, "문제 풀이" to 30)

    fun summary(result: List<com.github.nepyh.rooter.module.chat.RescheduledTask>) =
        result.map { "${it.existingId}:${it.taskName} ${it.startTime}~${it.endTime}" }

    "새 일정과 안 겹치는 태스크는 제자리에 두고, 겹치는 태스크만 옮긴다" {
        // 17:00~19:00 학원 → 16:10 태스크는 그대로, 17:00·18:00 태스크만 19:00 이후로
        val result = rescheduleKeepingUnaffected(existing, same, sleep + (m("17:00") to m("19:00")), earliestMinute = 0)

        summary(result) shouldBe listOf(
            "1:개념 정리 16:10~16:50",
            "2:암기 19:00~19:50",
            "3:문제 풀이 20:00~20:30"
        )
    }

    "오늘이면 옮기는 태스크를 지난 시간에 넣지 않는다" {
        // 지금 16:20, 16:30~17:30 학원 → 16:10 태스크는 겹쳐서 옮겨야 하는데, 아침 빈 시간이 아니라 17:30 이후로
        val result = rescheduleKeepingUnaffected(existing, same, sleep + (m("16:30") to m("17:30")), earliestMinute = m("16:20"))

        summary(result) shouldBe listOf(
            "3:문제 풀이 18:00~18:30",
            "1:개념 정리 18:30~19:10",
            "2:암기 19:20~20:10"
        )
    }

    "진행 중이거나 지난 태스크라도 새 일정과 안 겹치면 옮기지 않는다" {
        // 지금 16:30 (16:10 태스크 진행 중), 20~21시 학원 → 아무것도 안 겹침
        val result = rescheduleKeepingUnaffected(existing, same, sleep + (m("20:00") to m("21:00")), earliestMinute = m("16:30"))

        summary(result) shouldBe listOf("1:개념 정리 16:10~16:50", "2:암기 17:00~17:50", "3:문제 풀이 18:00~18:30")
    }

    "AI 가 소요시간을 줄이면 제자리에서 길이만 바뀌고, 새 태스크는 빈 시간에 들어가고, 뺀 태스크는 결과에 없다" {
        val ai = listOf("개념 정리" to 30, "새 복습" to 20) // 암기·문제 풀이는 AI 가 뺌
        val result = rescheduleKeepingUnaffected(existing, ai, sleep, earliestMinute = m("16:00"))

        // 기존 스케줄러와 같이 이미 있는 태스크 바로 뒤에 붙인다 (쉬는 시간은 새로 넣는 태스크끼리만)
        summary(result) shouldBe listOf("1:개념 정리 16:10~16:40", "null:새 복습 16:40~17:00")
    }

    "빈 시간이 모자라면 못 넣은 태스크는 빠진다" {
        // 16:00~23:00 학원 → 원래 시각(16:10) 이후엔 빈 시간이 없음. 아침으로 앞당기지도 않으므로 전부 빠진다
        rescheduleKeepingUnaffected(existing, same, sleep + (m("16:00") to m("23:00")), earliestMinute = 0) shouldBe emptyList()
    }
})
