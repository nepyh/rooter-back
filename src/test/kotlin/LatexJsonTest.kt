import com.github.nepyh.rooter.common.cleanQuizLlmJson
import com.github.nepyh.rooter.common.repairLatexJsonEscapes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 퀴즈 LLM 응답의 LaTeX 역슬래시 보정 (#388) */
class LatexJsonTest : StringSpec({

    @Serializable
    data class Q(val questionText: String, val choices: List<String> = emptyList())

    // 테스트 문자열은 raw string 이라 아래 \ 는 LLM 이 실제로 보낸 글자 그대로다
    fun parse(raw: String): Q = Json.decodeFromString(repairLatexJsonEscapes(raw))

    "역슬래시를 두 번 쓴 정상 응답은 그대로 둔다" {
        val raw = """{"questionText": "${'$'}\\frac{3}{4}${'$'} 의 값은?\n답하시오", "choices": ["${'$'}\\sqrt{2}${'$'}"]}"""
        repairLatexJsonEscapes(raw) shouldBe raw
        parse(raw) shouldBe Q("${'$'}\\frac{3}{4}${'$'} 의 값은?\n답하시오", listOf("${'$'}\\sqrt{2}${'$'}"))
    }

    "수식 안의 역슬래시 한 개는 JSON escape 로 읽히지 않게 두 개로 바꾼다 — \\frac·\\neq·\\times·\\beta·\\right" {
        val raw = """{"questionText": "${'$'}\frac{1}{2} \neq \times \beta \right)${'$'}"}"""
        parse(raw).questionText shouldBe "${'$'}\\frac{1}{2} \\neq \\times \\beta \\right)${'$'}"
    }

    "JSON 이 허용하지 않는 escape(\\sqrt·\\pi·\\cdot)도 파싱이 실패하지 않는다 — 수식 밖이어도" {
        val raw = """{"questionText": "${'$'}\sqrt{2} \cdot \pi${'$'} 그리고 밖의 \sqrt", "choices": ["${'$'}\le${'$'}"]}"""
        parse(raw) shouldBe Q("${'$'}\\sqrt{2} \\cdot \\pi${'$'} 그리고 밖의 \\sqrt", listOf("${'$'}\\le${'$'}"))
    }

    "수식 밖의 정상 escape(줄바꿈·따옴표·유니코드)는 그대로 읽는다" {
        val raw = """{"questionText": "첫 줄\n둘째 줄 \"인용\" é ${'$'}x^2${'$'}"}"""
        parse(raw).questionText shouldBe "첫 줄\n둘째 줄 \"인용\" é ${'$'}x^2${'$'}"
    }

    "수식이 없는 문항과 문자열 밖 구조는 바뀌지 않는다" {
        val raw = """[{"questionText": "광합성이 일어나는 곳은?", "choices": ["엽록체", "핵"]}]"""
        repairLatexJsonEscapes(raw) shouldBe raw
    }

    "문자열마다 수식 상태를 새로 시작한다 — 앞 문자열의 짝 안 맞는 \$ 가 다음 문자열로 번지지 않는다" {
        val raw = """{"questionText": "${'$'}x", "choices": ["\n줄바꿈"]}"""
        parse(raw) shouldBe Q("${'$'}x", listOf("\n줄바꿈"))
    }

    "cleanQuizLlmJson: ```json 코드블록으로 감싼 응답도 벗기고 LaTeX 를 바로잡아 읽는다" {
        val raw = "```json\n" + """{"questionText": "${'$'}\sqrt{2}${'$'} 는?"}""" + "\n```"
        Json.decodeFromString<Q>(cleanQuizLlmJson(raw)).questionText shouldBe "${'$'}\\sqrt{2}${'$'} 는?"
    }
})
