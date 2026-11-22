import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.requireValidPasswordFormat
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 새 비밀번호 형식 검사 (#385 — 공백 불가) */
class PasswordPolicyTest : StringSpec({

    "영문·숫자 포함 8자 이상이면 통과한다 (특수문자 허용)" {
        listOf("abcd1234", "Passw0rd!", "a1b2c3d4e5", "비번abcd1234").forEach { password ->
            shouldNotThrowAny { requireValidPasswordFormat(password) }
        }
    }

    "공백 문자가 하나라도 있으면 INVALID_PASSWORD_FORMAT" {
        listOf("abcd 1234", " abcd1234", "abcd1234 ", "abcd\t1234", "abcd\n1234", "abcd　" + "1234").forEach { password ->
            val exception = shouldThrow<UserValidationException.WrongPasswordFormatException> {
                requireValidPasswordFormat(password)
            }
            exception.code shouldBe "INVALID_PASSWORD_FORMAT"
        }
    }

    "기존 규칙(8자 미만·영문 없음·숫자 없음)도 그대로 거절한다" {
        listOf("abc1234", "12345678", "abcdefgh", "").forEach { password ->
            shouldThrow<UserValidationException.WrongPasswordFormatException> {
                requireValidPasswordFormat(password)
            }
        }
    }
})
