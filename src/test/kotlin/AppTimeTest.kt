import com.github.nepyh.rooter.common.todayInAppZone
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AppTimeTest : StringSpec({

    fun at(utc: String) = Clock.fixed(Instant.parse(utc), ZoneOffset.UTC)

    "todayInAppZone: UTC 로는 전날이어도 한국 시간 기준 날짜를 준다" {
        // 운영 서버(UTC) 에서 한국 시간 새벽 3시 — 서버의 LocalDate.now() 는 09-28 이지만 사용자에겐 09-29
        todayInAppZone(at("2026-09-28T18:00:00Z")) shouldBe LocalDate.of(2026, 9, 29)
    }

    "todayInAppZone: 한국 자정을 기준으로 날짜가 바뀐다" {
        todayInAppZone(at("2026-09-28T14:59:59Z")) shouldBe LocalDate.of(2026, 9, 28) // KST 23:59:59
        todayInAppZone(at("2026-09-28T15:00:00Z")) shouldBe LocalDate.of(2026, 9, 29) // KST 00:00:00
    }

    "todayInAppZone: 서버 JVM 기본 시간대와 무관하다" {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            todayInAppZone(at("2026-09-28T18:00:00Z")) shouldBe LocalDate.of(2026, 9, 29)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
})
