import com.github.nepyh.rooter.module.chat.EditDay
import com.github.nepyh.rooter.module.chat.EditableTask
import com.github.nepyh.rooter.module.chat.applyChatEdits
import com.github.nepyh.rooter.module.chat.dto.AiChatEdit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ChatEditsTest : StringSpec({

    fun m(time: String): Int = time.substringBefore(":").toInt() * 60 + time.substringAfter(":").toInt()

    // 평일: 하교 16:30 전과 23:00 취침 후는 막힘, 학원 19:00~20:00
    val weekday = EditDay(
        blocked = listOf(0 to m("16:30"), m("19:00") to m("20:00"), m("23:00") to 24 * 60),
        earliestMinute = 0,
        subjectIdsByName = mapOf("수학" to 1, "과학" to 2)
    )
    val math = EditableTask(id = 10, name = "수학 문제 풀기", start = m("17:00"), end = m("18:00"), subjectId = 1)
    val english = EditableTask(id = 11, name = "영어 단어", start = m("18:10"), end = m("18:40"))

    fun times(tasks: List<EditableTask>) = tasks.map { "${it.name} ${it.start / 60}:${"%02d".format(it.start % 60)}~${it.end / 60}:${"%02d".format(it.end % 60)}" }

    "줄이기: 시작 시각은 그대로 두고 끝 시각만 당긴다" {
        val result = applyChatEdits(listOf(math, english), listOf(AiChatEdit("resize", "수학 문제 풀기", minutes = 30)), weekday)

        times(result.tasks) shouldBe listOf("수학 문제 풀기 17:00~17:30", "영어 단어 18:10~18:40")
        result.applied.single() shouldBe "'수학 문제 풀기'을(를) 30분(17:00~17:30)으로 줄였어요."
    }

    "늘리기: 다음 할일과 겹치면 거절하고 빈 시간을 알려준다, 비어 있으면 늘린다" {
        val blocked = applyChatEdits(listOf(math, english), listOf(AiChatEdit("resize", "수학 문제 풀기", minutes = 90)), weekday)
        blocked.changed shouldBe false
        blocked.rejected.single() shouldContain "그 시간에 '영어 단어' 할일이 있어요"
        blocked.rejected.single() shouldContain "오늘 빈 시간: 16:30~17:00, 18:00~18:10, 18:40~19:00"

        val ok = applyChatEdits(listOf(math), listOf(AiChatEdit("resize", "수학 문제 풀기", minutes = 90)), weekday)
        times(ok.tasks) shouldBe listOf("수학 문제 풀기 17:00~18:30")
    }

    "옮기기: 길이는 그대로, 빈 시각이면 옮기고 다른 할일·학원·지난 시간과 겹치면 거절한다" {
        times(applyChatEdits(listOf(math, english), listOf(AiChatEdit("move", "수학 문제 풀기", start_time = "21:00")), weekday).tasks) shouldBe
            listOf("영어 단어 18:10~18:40", "수학 문제 풀기 21:00~22:00")

        applyChatEdits(listOf(math, english), listOf(AiChatEdit("move", "수학 문제 풀기", start_time = "18:00")), weekday)
            .rejected.single() shouldContain "'영어 단어' 할일이 있어요"
        applyChatEdits(listOf(math), listOf(AiChatEdit("move", "수학 문제 풀기", start_time = "19:30")), weekday)
            .rejected.single() shouldContain "잠·학교·학원 같은 다른 일정과 겹쳐요"
        val tonight = EditDay(weekday.blocked, earliestMinute = m("20:10"), subjectIdsByName = emptyMap())
        applyChatEdits(listOf(math), listOf(AiChatEdit("move", "수학 문제 풀기", start_time = "17:30")), tonight)
            .rejected.single() shouldContain "이미 지난 시간이에요"
    }

    "빼기: AI 가 만든 할일은 챗봇으로도 못 뺀다 (옮기기·줄이기는 된다)" {
        val aiTask = math.copy(aiGenerated = true)

        val rejected = applyChatEdits(listOf(aiTask, english), listOf(AiChatEdit("delete", "수학 문제 풀기")), weekday)
        rejected.changed shouldBe false
        rejected.deletedIds shouldBe emptySet<Int>()
        rejected.rejected.single() shouldBe "'수학 문제 풀기'은(는) AI 가 만든 할일이라 뺄 수 없어요. 할일을 빼려면 직접 추가한 할일이어야 해요."

        times(applyChatEdits(listOf(aiTask), listOf(AiChatEdit("resize", "수학 문제 풀기", minutes = 30)), weekday).tasks) shouldBe
            listOf("수학 문제 풀기 17:00~17:30")
        times(applyChatEdits(listOf(aiTask), listOf(AiChatEdit("move", "수학 문제 풀기", start_time = "21:00")), weekday).tasks) shouldBe
            listOf("수학 문제 풀기 21:00~22:00")
    }

    "빼기: 퀴즈를 시작하지 않은 할일만 빼고, 퀴즈를 시작한 할일은 거절한다" {
        val result = applyChatEdits(listOf(math, english), listOf(AiChatEdit("delete", "영어 단어")), weekday)
        result.deletedIds shouldBe setOf(11)
        times(result.tasks) shouldBe listOf("수학 문제 풀기 17:00~18:00")

        applyChatEdits(listOf(math.copy(quizStarted = true)), listOf(AiChatEdit("delete", "수학 문제 풀기")), weekday)
            .rejected.single() shouldBe "'수학 문제 풀기'은(는) 이미 퀴즈를 시작한 할일이라 뺄 수 없어요."
    }

    "퀴즈로 결과가 확정된 할일은 줄이기·옮기기도 거절한다" {
        val finalized = math.copy(quizStarted = true, quizFinalized = true)
        val result = applyChatEdits(
            listOf(finalized),
            listOf(AiChatEdit("resize", "수학 문제 풀기", minutes = 30), AiChatEdit("move", "수학 문제 풀기", start_time = "21:00")),
            weekday
        )
        result.changed shouldBe false
        result.rejected.size shouldBe 2
    }

    "추가: 시각을 주면 그 시각이 비어 있을 때만, 과목은 플랜보드 과목명으로 붙인다" {
        val result = applyChatEdits(listOf(math), listOf(AiChatEdit("add", "과학 2단원 개념 정리", minutes = 40, start_time = "20:00", subject_name = "과학")), weekday)
        times(result.tasks) shouldBe listOf("수학 문제 풀기 17:00~18:00", "과학 2단원 개념 정리 20:00~20:40")
        result.tasks.last().let { (it.id to it.subjectId) } shouldBe (null to 2)

        applyChatEdits(listOf(math), listOf(AiChatEdit("add", "과학", minutes = 40, start_time = "17:30")), weekday)
            .rejected.single() shouldContain "'수학 문제 풀기' 할일이 있어요"
    }

    "추가: 시각이 없으면 그날 가장 이른 빈 시간에 다른 할일과 10분 띄워 넣고, 자리가 없으면 거절한다" {
        // 16:30~17:00 은 30분이라 40분이 안 들어가고, 수학 뒤 18:10 부터
        times(applyChatEdits(listOf(math), listOf(AiChatEdit("add", "과학 복습", minutes = 40)), weekday).tasks) shouldBe
            listOf("수학 문제 풀기 17:00~18:00", "과학 복습 18:10~18:50")

        val noRoom = EditDay(listOf(0 to 24 * 60), 0, emptyMap())
        applyChatEdits(emptyList(), listOf(AiChatEdit("add", "과학 복습", minutes = 40)), noRoom)
            .rejected.single() shouldBe "오늘은 '과학 복습'(40분)이 들어갈 빈 시간이 없어요."
    }

    "여러 편집은 순서대로 적용해 앞 편집이 비운 자리를 뒤 편집이 쓴다" {
        val result = applyChatEdits(
            listOf(math, english),
            listOf(
                AiChatEdit("delete", "영어 단어"),
                AiChatEdit("add", "과학 개념", minutes = 30, start_time = "18:10")
            ),
            weekday
        )
        times(result.tasks) shouldBe listOf("수학 문제 풀기 17:00~18:00", "과학 개념 18:10~18:40")
        result.applied.size shouldBe 2
    }

    "없는 할일 이름, 범위 밖 분, 모르는 동작은 거절한다 — 이름은 하나만 맞으면 일부만 같아도 찾는다" {
        val result = applyChatEdits(
            listOf(math, english),
            listOf(
                AiChatEdit("resize", "국어", minutes = 30),
                AiChatEdit("resize", "수학", minutes = 300),
                AiChatEdit("rename", "수학 문제 풀기"),
                AiChatEdit("resize", "수학", minutes = 45)
            ),
            weekday
        )
        result.rejected shouldBe listOf(
            "'국어' 할일을 오늘 계획에서 찾지 못했어요.",
            "'수학 문제 풀기'은(는) 5~240분 사이로만 바꿀 수 있어요.",
            "그 요청은 처리하지 못했어요."
        )
        result.applied.single() shouldBe "'수학 문제 풀기'을(를) 45분(17:00~17:45)으로 줄였어요."
    }
})
