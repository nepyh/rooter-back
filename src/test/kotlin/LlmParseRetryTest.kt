import com.github.nepyh.rooter.common.LLM_RESPONSE_JSON
import com.github.nepyh.rooter.common.cleanQuizLlmJson
import com.github.nepyh.rooter.common.requestParsedWithRetry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** 퀴즈 AI 응답 파싱이 실패하면 같은 요청을 한 번 더 보낸다 (#402) */
class LlmParseRetryTest : StringSpec({

    @Serializable
    data class Question(val question_text: String, val choices: List<String>)

    @Serializable
    data class Quiz(val questions: List<Question>)

    val logger = LoggerFactory.getLogger(LlmParseRetryTest::class.java)
    fun parse(content: String): List<Question> = LLM_RESPONSE_JSON.decodeFromString<Quiz>(cleanQuizLlmJson(content)).questions

    // 11-24 운영 로그에서 실패한 응답 그대로 — question_text 값이 따옴표 없이 수식으로 시작한다
    val broken = """{"questions": [{"question_text": ${'$'}(x+3)(x+4)${'$'}를 전개한 결과는?, "choices": ["${'$'}x^2+7x+12${'$'}", "${'$'}x^2+12${'$'}"]}]}"""
    val fixed = """{"questions": [{"question_text": "${'$'}(x+3)(x+4)${'$'}를 전개한 결과는?", "choices": ["${'$'}x^2+7x+12${'$'}", "${'$'}x^2+12${'$'}"]}]}"""

    "첫 응답 형식이 틀리면 다시 불러 두 번째 응답을 쓴다" {
        val responses = ArrayDeque(listOf(broken, fixed))
        var calls = 0

        val result = requestParsedWithRetry(logger, { calls++; responses.removeFirst() }) { parse(it) }

        calls shouldBe 2
        result?.single()?.question_text shouldBe "${'$'}(x+3)(x+4)${'$'}를 전개한 결과는?"
    }

    "첫 응답이 맞으면 다시 부르지 않는다" {
        var calls = 0

        val result = requestParsedWithRetry(logger, { calls++; fixed }) { parse(it) }

        calls shouldBe 1
        result?.size shouldBe 1
    }

    "두 번 모두 형식이 틀리면 null — 세 번째는 부르지 않는다" {
        var calls = 0

        val result = requestParsedWithRetry(logger, { calls++; broken }) { parse(it) }

        calls shouldBe 2
        result shouldBe null
    }

    "호출 자체가 실패하면(null) 다시 부르지 않는다" {
        var calls = 0

        val result = requestParsedWithRetry(logger, { calls++; null }) { parse(it) }

        calls shouldBe 1
        result shouldBe null
    }
})
