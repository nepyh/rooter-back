import com.github.nepyh.rooter.module.chat.ExistingPendingTask
import com.github.nepyh.rooter.module.chat.OtherBusyDay
import com.github.nepyh.rooter.module.chat.OtherBusyDays
import com.github.nepyh.rooter.module.chat.RECURRING_HINT
import com.github.nepyh.rooter.module.chat.dto.AiChatBusyDay
import com.github.nepyh.rooter.module.chat.otherBusyDaysOf
import com.github.nepyh.rooter.module.chat.otherDaysReply
import com.github.nepyh.rooter.module.chat.rescheduleOtherDay
import com.github.nepyh.rooter.module.chat.todayText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class ChatOtherDaysTest : StringSpec({

    fun m(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val sleep = listOf(0 to m("06:30"), m("23:00") to 24 * 60)

    val planDate = LocalDate.of(2026, 11, 2) // 월
    val boardStart = LocalDate.of(2026, 10, 26)
    val boardEnd = LocalDate.of(2026, 11, 8)

    "otherBusyDaysOf: 형식이 틀리거나 대화한 날인 줄은 버리고, 같은 날 시간대는 묶고, 지난 날·기간 밖은 따로 모은다" {
        val result = otherBusyDaysOf(
            listOf(
                AiChatBusyDay("2026-11-05", "19:00", "21:00"),
                AiChatBusyDay("2026-11-04", "19:00", "21:00"),
                AiChatBusyDay("2026-11-04", "07:00", "08:00"),
                AiChatBusyDay("2026-11-02", "19:00", "21:00"), // 대화한 날 — plan_update 로 따로 처리
                AiChatBusyDay("2026-11-06", "21:00", "19:00"), // 시작이 끝보다 늦음
                AiChatBusyDay("수요일", "19:00", "21:00"), // 날짜 형식 아님
                AiChatBusyDay("2026-11-01", "19:00", "21:00"), // 지난 날
                AiChatBusyDay("2026-11-10", "19:00", "21:00") // 플랜보드 기간 밖
            ),
            planDate = planDate, today = planDate, boardStart = boardStart, boardEnd = boardEnd
        )

        result shouldBe OtherBusyDays(
            days = listOf(
                OtherBusyDay(LocalDate.of(2026, 11, 4), listOf(m("19:00") to m("21:00"), m("07:00") to m("08:00"))),
                OtherBusyDay(LocalDate.of(2026, 11, 5), listOf(m("19:00") to m("21:00")))
            ),
            outOfRange = listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10))
        )
    }

    "otherBusyDaysOf: 끝 시각 24:00 은 하루 끝으로 받는다 (\"8시 이후 못 해\")" {
        val result = otherBusyDaysOf(
            listOf(AiChatBusyDay("2026-11-04", "20:00", "24:00")),
            planDate = planDate, today = planDate, boardStart = boardStart, boardEnd = boardEnd
        )

        result.days shouldBe listOf(OtherBusyDay(LocalDate.of(2026, 11, 4), listOf(m("20:00") to m("23:59"))))
    }

    "todayText: 오늘 날짜에 이번 주·다음 주(월~일) 범위를 붙인다" {
        todayText(LocalDate.of(2026, 11, 8)) shouldBe "2026-11-08 (일요일)\n" +
            "이번 주: 2026-11-02(월) ~ 2026-11-08(일)\n" +
            "다음 주: 2026-11-09(월) ~ 2026-11-15(일)"
    }

    "rescheduleOtherDay: 새 일정과 겹치는 할일만 원래 시각 이후로 옮기고, 안 겹치는 할일·AI 가 만든 할일은 제자리" {
        val pending = listOf(
            ExistingPendingTask(1, "개념 정리", m("17:10"), m("17:50")),
            ExistingPendingTask(2, "암기", m("19:10"), m("20:00")),
            ExistingPendingTask(3, "AI 복습", m("20:10"), m("20:40"), aiGenerated = true)
        )

        val result = rescheduleOtherDay(pending, listOf(m("19:00") to m("21:00")), sleep, earliestMinute = 0)!!

        result.moved.map { "${it.existingId}:${it.taskName} ${it.startTime}~${it.endTime}" } shouldBe listOf("2:암기 21:00~21:50")
        result.leftInPlace shouldBe emptyList()
    }

    "rescheduleOtherDay: 겹치는 할일이 없으면 null, 빈 시간이 없으면 지우지 않고 그대로 둔다" {
        val pending = listOf(ExistingPendingTask(1, "암기", m("19:10"), m("20:00")))

        rescheduleOtherDay(pending, listOf(m("21:00") to m("22:00")), sleep, earliestMinute = 0) shouldBe null

        val full = rescheduleOtherDay(pending, listOf(m("19:00") to m("23:00")), sleep, earliestMinute = 0)!!
        full.moved shouldBe emptyList()
        full.leftInPlace shouldBe listOf("암기")
    }

    "otherDaysReply: 옮긴 날·그대로인 날·기간 밖인 날을 실제 결과대로 답한다" {
        val wed = LocalDate.of(2026, 11, 4)
        val thu = LocalDate.of(2026, 11, 5)
        val busy = OtherBusyDays(
            days = listOf(OtherBusyDay(wed, listOf(m("19:00") to m("21:00"))), OtherBusyDay(thu, listOf(m("19:00") to m("21:00")))),
            outOfRange = listOf(LocalDate.of(2026, 11, 10))
        )
        val wedResult = rescheduleOtherDay(
            listOf(ExistingPendingTask(1, "암기", m("19:10"), m("20:00"))), busy.days[0].windows, sleep, earliestMinute = 0
        )

        otherDaysReply(busy, mapOf(wed to wedResult, thu to null), boardStart, boardEnd) shouldBe
            "11월 4일(수) 19:00~21:00 일정을 피해서 '암기' 할일을 21:00~21:50로 옮겼어요. " +
            "11월 5일(목)에는 그 시간에 잡힌 할일이 없어서 그대로예요. " +
            "11월 10일(화)은(는) 지난 날이거나 플랜보드 기간(10월 26일(월)~11월 8일(일)) 밖이라 바꾸지 않았어요."
    }

    "otherDaysReply: 같은 요일이 두 번 이상이면 불가능 시간 등록을 권한다" {
        val wed = LocalDate.of(2026, 11, 4)
        val busy = OtherBusyDays(
            days = listOf(OtherBusyDay(wed, listOf(m("19:00") to m("21:00")))),
            outOfRange = listOf(wed.plusWeeks(1))
        )

        otherDaysReply(busy, mapOf(wed to null), boardStart, boardEnd).endsWith(RECURRING_HINT) shouldBe true
    }
})
