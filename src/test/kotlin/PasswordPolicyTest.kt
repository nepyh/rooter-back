import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.requireValidPasswordFormat
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 새 비밀번호 형식 검사 (#385) */
class PasswordPolicyTest : StringSpec({

    fun shouldReject(password: String) {
        val exception = shouldThrow<UserValidationException.WrongPasswordFormatException> {
            requireValidPasswordFormat(password)
        }
        exception.code shouldBe "INVALID_PASSWORD_FORMAT"
    }

    "영문·숫자 포함 8~20자면 통과한다 (특수문자·한글 허용)" {
        listOf(
            "abcd1234",
            "Passw0rd!@#\$%^&*()",
            "a1b2c3d4e5f6g7h8i9j0", // 20자
            "비번abcd1234",
            "가나다라마바사아자차카타파하abcd12" // 한글 14자 + 6자 = 20자, UTF-8 48바이트
        ).forEach { password ->
            shouldNotThrowAny { requireValidPasswordFormat(password) }
        }
    }

    "기존 규칙(8자 미만·영문 없음·숫자 없음)은 그대로 거절한다" {
        listOf("abc1234", "12345678", "abcdefgh", "").forEach(::shouldReject)
    }

    "20자를 넘으면 거절한다 (BCrypt 는 앞 72바이트만 쓴다)" {
        shouldReject("a1b2c3d4e5f6g7h8i9j0k") // 21자
        shouldReject("가".repeat(30) + "a1") // 한글 30자 = 90바이트, 72바이트를 넘는 경우
    }

    "공백 문자는 정규식이 통과시키는 것까지 거절한다" {
        // 줄바꿈은 정규식만으로도 막히므로 여기서는 정규식이 통과시키는 공백만 둔다
        listOf("abcd 1234", " abcd1234", "abcd1234 ", "abcd\t1234", "abcd\u00A01234", "abcd\u30001234")
            .forEach(::shouldReject)
    }

    "눈에 안 보이는 서식 문자를 거절한다 (붙여넣기로 섞여 들어오는 것)" {
        listOf("abcd1234\u200B", "abcd\u200C1234", "abcd\u200D1234", "abcd\u20601234", "\uFEFFabcd1234")
            .forEach(::shouldReject)
    }

    "이모지를 거절한다 — 보조 평면, BMP 이모지, 변형 선택자·키캡" {
        listOf(
            "abc12😀😀", // 보이는 글자 7자지만 UTF-16 으로 9 — 글자 수가 아니라 이모지로 막힌다
            "abcd1234😀",
            "abcd1234☺",
            "abcd1234❤",
            "abcd1234✅",
            "abcd1234\uFE0F",
            "abcd12341\u20E3"
        ).forEach(::shouldReject)
    }
})
