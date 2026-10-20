import com.github.nepyh.rooter.module.studystyle.BestTime
import com.github.nepyh.rooter.module.studystyle.DailyStudyHabit
import com.github.nepyh.rooter.module.studystyle.Distraction
import com.github.nepyh.rooter.module.studystyle.FocusSpan
import com.github.nepyh.rooter.module.studystyle.LearningMethod
import com.github.nepyh.rooter.module.studystyle.StudyStyleProfile
import com.github.nepyh.rooter.module.studystyle.UnfinishedHabit
import com.github.nepyh.rooter.module.studystyle.VolatileTime
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class StudyStyleProfileTest : StringSpec({

    "문항 번호와 보기 번호를 프론트 설문 순서대로 해석한다" {
        StudyStyleProfile.fromAnswers(mapOf(1 to 1, 2 to 3, 3 to 2, 4 to 3, 5 to 1, 6 to 1, 7 to 2)) shouldBe StudyStyleProfile(
            focus = FocusSpan.SHORT,
            bestTime = BestTime.NIGHT,
            method = LearningMethod.PROBLEM_FIRST,
            volatileTime = VolatileTime.WEEKEND,
            dailyStudy = DailyStudyHabit.UNDER_30,
            unfinished = UnfinishedHabit.POSTPONE,
            distraction = Distraction.SLEEPY
        )
    }

    "4(모르겠어요)·미응답·범위 밖 문항과 보기는 null 로 두어 계획에 영향을 주지 않는다" {
        StudyStyleProfile.fromAnswers(mapOf(1 to 4, 2 to 2, 8 to 1, 3 to 0)) shouldBe StudyStyleProfile(bestTime = BestTime.AFTERNOON)
        StudyStyleProfile.fromAnswers(emptyMap()) shouldBe StudyStyleProfile.EMPTY
    }

    "설문이 비어 있으면 계획 규칙은 빈 문자열, 요약은 '응답 없음'" {
        StudyStyleProfile.EMPTY.planRules(emptyList()) shouldBe ""
        StudyStyleProfile.EMPTY.summary() shouldBe "응답 없음"
    }

    "계획 규칙에는 고른 문항만 성향과 지시를 함께 넣는다" {
        val rules = StudyStyleProfile(focus = FocusSpan.SHORT, unfinished = UnfinishedHabit.SKIP).planRules(emptyList())

        rules shouldContain "한 번에 집중: 짧고 굵게 (20~30분 공부 + 5분 휴식) → task 하나의 estimated_minutes 는 30분을 넘기지 마라."
        rules shouldContain "계획을 못 끝냈을 때: 그냥 넘어간다 →"
        rules shouldNotContain "공부가 잘 되는 시간대"
    }

    "주말이 변수인 학생에게는 이 구간의 주말 day 번호를 알려준다" {
        val profile = StudyStyleProfile(volatileTime = VolatileTime.WEEKEND)

        profile.planRules(listOf(6, 7)) shouldContain "주말(day 6, 7)은 평일보다 분량을 가볍게"
        profile.planRules(emptyList()) shouldContain "이 구간에는 주말이 없다."
    }

    "챗봇 요약은 계획 지시 없이 고른 보기 문구만 담는다" {
        val summary = StudyStyleProfile(bestTime = BestTime.MORNING, distraction = Distraction.PHONE).summary()

        summary shouldBe "공부가 잘 되는 시간대: 아침 (등교 전)\n가장 큰 방해 요소: 스마트폰 알림"
    }
})
