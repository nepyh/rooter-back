import com.github.nepyh.rooter.common.LLM_RESPONSE_JSON
import com.github.nepyh.rooter.common.stripCodeFence
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LlmOptionsTest : StringSpec({

    "stripCodeFence: ```json 코드블록으로 감싼 응답에서 JSON 만 꺼낸다" {
        stripCodeFence("```json\n{\"daily_plans\": []}\n```") shouldBe "{\"daily_plans\": []}"
        stripCodeFence("```\n[1, 2]\n```\n") shouldBe "[1, 2]"
        stripCodeFence("```json{\"a\": 1}```") shouldBe "{\"a\": 1}"
    }

    "stripCodeFence: 코드블록이 아니면 앞뒤 공백만 뺀다 (JSON 안의 ``` 는 건드리지 않는다)" {
        stripCodeFence("  {\"a\": \"```\"}\n") shouldBe "{\"a\": \"```\"}"
    }

    "LLM_RESPONSE_JSON: 배열·객체 끝 쉼표와 모르는 키를 허용한다 (AI 가 가끔 \"\", ] 처럼 보냄)" {
        LLM_RESPONSE_JSON.decodeFromString<Map<String, List<String>>>("""{"choice_reasons": ["", "이유", ], }""") shouldBe
            mapOf("choice_reasons" to listOf("", "이유"))
    }
})
