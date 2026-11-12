import com.github.nepyh.rooter.common.PromptLoader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PromptLoaderTest : StringSpec({

    "자리표시자를 한 번에 치환해서, 넣은 값 안의 {{...}} 글자는 다시 치환되지 않고 넘기지 않은 자리표시자는 그대로 둔다" {
        PromptLoader.load(
            "prompts/prompt-loader-test.md",
            "USER_MESSAGE" to "{{SESSION_TITLE_NEEDED}} 라고 써 볼게",
            "SESSION_TITLE_NEEDED" to "아니오"
        ) shouldBe "메시지: {{SESSION_TITLE_NEEDED}} 라고 써 볼게\n제목 필요: 아니오\n안 넘긴 값: {{MISSING}}\n"
    }
})
