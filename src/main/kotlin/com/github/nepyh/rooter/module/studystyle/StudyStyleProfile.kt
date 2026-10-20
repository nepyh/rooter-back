package com.github.nepyh.rooter.module.studystyle

import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerRow
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
import org.jetbrains.exposed.v1.core.eq

/*
 * 공부 스타일 설문 7문항의 해석.
 * 문항·보기 순서는 rooter-front `app/auth/StudySurveyQuestion.tsx` 와 묶인 계약이다 (보기 4 = "모르겠어요").
 * 각 보기는 학생에게 보여준 문구(label)와, 계획 생성 AI 에게 줄 지시(planRule)를 함께 들고 있다.
 * 4(모르겠어요)·미응답 문항은 null 이고, null 이면 그 문항은 계획에 아무 영향도 주지 않는다.
 */

/** Q1 한 번에 최대로 집중할 수 있는 시간 */
enum class FocusSpan(val label: String, val maxTaskMinutes: Int, val breakMinutes: Int) {
    SHORT("짧고 굵게 (20~30분 공부 + 5분 휴식)", 30, 5),
    STANDARD("표준 (45~50분 공부 + 10분 휴식)", 50, 10),
    DEEP("몰입형 (90분 이상 쉬지 않고 진도 빼기)", 90, 15);

    val planRule get() = "task 하나의 estimated_minutes 는 ${maxTaskMinutes}분을 넘기지 마라. 더 긴 공부는 여러 task 로 나눠라."
}

/** Q2 하루 중 공부가 가장 잘 되는 시간대 */
enum class BestTime(val label: String, val planRule: String) {
    MORNING("아침 (등교 전)", "그날 tasks 중 새 진도·어려운 task 를 맨 앞에 둬라 (등교하지 않는 날은 아침부터 배치된다)."),
    AFTERNOON("오후 (학교 마친 직후)", "그날 tasks 중 새 진도·어려운 task 를 맨 앞에 둬라 (하교 직후부터 배치된다)."),
    NIGHT("밤 (자기 전)", "그날 tasks 중 새 진도·어려운 task 를 맨 앞에 둬라 (tasks 는 잠들기 전 시간대에 몰아서 배치된다).")
}

/** Q3 새로운 내용을 공부할 때 선호하는 방식 */
enum class LearningMethod(val label: String, val planRule: String) {
    TEXTBOOK("교과서를 처음부터 차근차근 읽기", "새 단원은 '교과서 개념 읽고 정리' task 다음에 '문제 풀기' task 순서로 잡아라."),
    PROBLEM_FIRST("문제부터 풀어보고 안 풀리는 부분만 찾아보기", "새 단원은 '기본 문제 먼저 풀기' task 다음에 '틀린 문제 개념 확인' task 순서로 잡아라."),
    LECTURE_FIRST("인강이나 영상으로 먼저 훑어보기", "새 단원은 '인강·영상으로 개념 훑기' → '교과서로 정리' → '문제 풀기' 순서로 잡아라.")
}

/** Q4 하루 공부 스케줄에서 가장 변수가 많은 시간대 */
enum class VolatileTime(val label: String) {
    AFTER_SCHOOL("방과 후 ~ 저녁 식사 전 (학원, 친구, 집 오자마자 쉬는 시간)"),
    EVENING("저녁 식사 후 ~ 밤 (스마트폰, 딴짓, 집중력 저하)"),
    WEEKEND("주말 전체 (수면 패턴 불규칙, 야외 활동 등)");

    /** [weekendDays]: 이 구간에서 토·일요일인 day 번호 */
    fun planRule(weekendDays: List<Int>): String = when (this) {
        AFTER_SCHOOL, EVENING -> "이 시간대는 배치할 때 최대한 피하므로 따로 맞출 필요는 없다."
        WEEKEND -> if (weekendDays.isEmpty()) {
            "이 구간에는 주말이 없다."
        } else {
            "주말(day ${weekendDays.joinToString(", ")})은 평일보다 분량을 가볍게 하고, 못 한 공부를 채울 여유를 남겨라."
        }
    }
}

/** Q5 시험 기간이 아닐 때 평소 하루 자습 시간 */
enum class DailyStudyHabit(val label: String, val planRule: String) {
    UNDER_30("30분 이하", "평소 공부량이 적으니 처음 2~3일은 하루 총 공부량을 60분 안팎으로 가볍게 시작해 점점 늘려라."),
    UP_TO_60("30분 ~ 1시간", "처음 2~3일은 하루 총 공부량을 90분 안팎에서 시작해 점점 늘려라."),
    OVER_60("1시간 이상", "평소 공부 습관이 있으니 처음부터 학년·실력 등급에 맞는 분량으로 잡아도 된다.")
}

/** Q6 계획한 공부를 다 못 끝냈을 때 */
enum class UnfinishedHabit(val label: String, val planRule: String) {
    POSTPONE("다음 날로 미루고 이어서 한다", "3~4일마다 하루는 새 진도를 줄이고 '밀린 공부 따라잡기' task(30분 안팎)를 넣어라."),
    FINISH_TODAY("그날 안에 끝까지 마무리한다", "하루 분량은 그날 안에 끝낼 수 있는 만큼만 정확히 잡아라."),
    SKIP("그냥 넘어간다", "task 를 작게 쪼개고 그날 꼭 해야 할 핵심 task 를 앞에 둬라. 놓친 내용을 챙기도록 복습 task 에 이전 날 내용도 포함해라.")
}

/** Q7 공부할 때 가장 크게 방해가 되는 요소 */
enum class Distraction(val label: String, val planRule: String) {
    PHONE("스마트폰 알림", "tips 에 스마트폰 알림을 끄거나 멀리 두는 방법을 넣어라."),
    SLEEPY("졸음/피로", "tips 에 졸음을 줄이는 방법(짧은 스트레칭, 어려운 과목 먼저)을 넣어라. 잠들기 직전 1시간은 배치할 때 피한다."),
    NOISE("소음이나 주변 환경", "tips 에 조용한 공부 환경을 만드는 방법을 넣어라.")
}

data class StudyStyleProfile(
    val focus: FocusSpan? = null,
    val bestTime: BestTime? = null,
    val method: LearningMethod? = null,
    val volatileTime: VolatileTime? = null,
    val dailyStudy: DailyStudyHabit? = null,
    val unfinished: UnfinishedHabit? = null,
    val distraction: Distraction? = null
) {

    /** 학생이 고른 보기 문구 요약. 챗봇처럼 계획 규칙 없이 성향만 알려줄 때 쓴다. 고른 게 없으면 "응답 없음". */
    fun summary(): String = listOfNotNull(
        focus?.let { "한 번에 집중: ${it.label}" },
        bestTime?.let { "공부가 잘 되는 시간대: ${it.label}" },
        method?.let { "새 내용 공부 방식: ${it.label}" },
        volatileTime?.let { "변수가 많은 시간대: ${it.label}" },
        dailyStudy?.let { "평소 하루 자습 시간: ${it.label}" },
        unfinished?.let { "계획을 못 끝냈을 때: ${it.label}" },
        distraction?.let { "가장 큰 방해 요소: ${it.label}" }
    ).joinToString("\n").ifBlank { "응답 없음" }

    /** 계획 생성 AI 에게 줄 문항별 "성향 → 지시" 줄. 고른 게 없으면 빈 문자열. */
    fun planRules(weekendDays: List<Int>): String = listOfNotNull(
        focus?.let { "- 한 번에 집중: ${it.label} → ${it.planRule}" },
        bestTime?.let { "- 공부가 잘 되는 시간대: ${it.label} → ${it.planRule}" },
        method?.let { "- 새 내용 공부 방식: ${it.label} → ${it.planRule}" },
        volatileTime?.let { "- 변수가 많은 시간대: ${it.label} → ${it.planRule(weekendDays)}" },
        dailyStudy?.let { "- 평소 하루 자습 시간: ${it.label} → ${it.planRule}" },
        unfinished?.let { "- 계획을 못 끝냈을 때: ${it.label} → ${it.planRule}" },
        distraction?.let { "- 가장 큰 방해 요소: ${it.label} → ${it.planRule}" }
    ).joinToString("\n")

    companion object {
        val EMPTY = StudyStyleProfile()

        /** questionNumber(1~7) → answerOption(1~4). 범위 밖 문항·보기와 4(모르겠어요)는 무시한다. */
        fun fromAnswers(answers: Map<Int, Int>): StudyStyleProfile {
            fun <T> pick(questionNumber: Int, options: Array<T>): T? =
                answers[questionNumber]?.let { options.getOrNull(it - 1) }

            return StudyStyleProfile(
                focus = pick(1, FocusSpan.entries.toTypedArray()),
                bestTime = pick(2, BestTime.entries.toTypedArray()),
                method = pick(3, LearningMethod.entries.toTypedArray()),
                volatileTime = pick(4, VolatileTime.entries.toTypedArray()),
                dailyStudy = pick(5, DailyStudyHabit.entries.toTypedArray()),
                unfinished = pick(6, UnfinishedHabit.entries.toTypedArray()),
                distraction = pick(7, Distraction.entries.toTypedArray())
            )
        }
    }
}

/** 사용자가 저장한 설문으로 만든 [StudyStyleProfile]. 설문을 안 했으면 [StudyStyleProfile.EMPTY]. 트랜잭션 안에서 부른다. */
fun userStudyStyle(userId: Int): StudyStyleProfile =
    StudyStyleProfile.fromAnswers(
        StudyStyleAnswerRow.find { StudyStyleAnswerTable.userId eq userId }
            .associate { it.questionNumber.toInt() to it.answerOption.toInt() }
    )
