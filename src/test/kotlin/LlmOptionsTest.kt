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
})
