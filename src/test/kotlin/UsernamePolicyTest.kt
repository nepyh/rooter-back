import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.requireValidUsername
import com.github.nepyh.rooter.module.user.usernameFromEmail
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 사용자 이름 검사 (#407) */
class UsernamePolicyTest : StringSpec({

    fun rejectMessage(username: String): String? {
        val exception = shouldThrow<UserValidationException.WrongUsernameException> { requireValidUsername(username) }
        exception.code shouldBe "INVALID_USERNAME"
        return exception.message
    }

    "1~12자 한글·영문·숫자·기호는 통과한다" {
        listOf("철", "김철수", "chulsoo_01", "가나다라마바사아자차카타", "a.b-c").forEach { name ->
            shouldNotThrowAny { requireValidUsername(name) }
        }
    }

    "빈 이름은 거절한다" {
        rejectMessage("") shouldBe "이름을 입력해주세요."
    }

    "띄어쓰기는 앞뒤·중간, 전각 공백·NBSP·탭까지 거절한다" {
        listOf("김 철수", " 철수", "철수 ", "   ", "김　철수", "김 철수", "김\t철수").forEach { name ->
            rejectMessage(name) shouldBe "이름에 띄어쓰기를 넣을 수 없습니다."
        }
    }

    "이모지·보이지 않는 문자는 거절한다" {
        listOf("철수😀", "철수❤", "철수​", "﻿철수", "철수\u0007").forEach { name ->
            rejectMessage(name) shouldBe "이름에 이모지나 보이지 않는 문자는 넣을 수 없습니다."
        }
    }

    "13자는 거절한다" {
        rejectMessage("가".repeat(13)) shouldBe "이름은 12자 이하여야 합니다."
    }

    "소셜 가입 이름은 이메일 앞부분을 규칙에 맞게 다듬는다" {
        usernameFromEmail("student.kim+rooter2026@gmail.com") shouldBe "student.kim+"
        usernameFromEmail("abc@test.com") shouldBe "abc"
        usernameFromEmail("\"a b\"@test.com") shouldBe "\"ab\""
        usernameFromEmail("@test.com") shouldBe "user"
        listOf("student.kim+rooter2026@gmail.com", "\"a b\"@test.com", "@test.com").forEach {
            shouldNotThrowAny { requireValidUsername(usernameFromEmail(it)) }
        }
    }
})
