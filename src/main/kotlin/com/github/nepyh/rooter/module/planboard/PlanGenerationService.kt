package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.leveltest.model.LevelTestResults
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationDailyResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationSubjectInput
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.Chapters
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjects
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.planboard.model.Textbooks
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private val DEFAULT_UNAVAILABLE_RANGES = listOf(0 to (6 * 60 + 30), (23 * 60) to (24 * 60)) // 00:00~06:30, 23:00~24:00
private val SCHOOL_PREP_RANGE = (7 * 60) to (8 * 60) // 07:00~08:00, 등교 준비(세면/식사/이동), 평일만
private const val SCHOOL_START_MINUTES = 8 * 60 + 30 // 08:30 등교, 고정
private val DEFAULT_SCHOOL_HOURS = SCHOOL_START_MINUTES to (16 * 60 + 30) // NICE 시간표를 못 가져올 때 쓰는 폴백값 (08:30~16:30)

/**
 * 하교 시각 = 09:10 + (그날 마지막 교시 수 × 60분).
 * "6교시면 15:10, 7교시면 16:10" 두 지점으로부터 도출한 선형식 — NICE 는 교시 번호만 주고
 * 실제 시각(등/하교 종 치는 시각)은 학교마다 달라서 공공데이터로 안 열려있기 때문에 근사치로 씀.
 */
private fun dismissalMinutesForLastPeriod(lastPeriod: Int): Int = (9 * 60 + 10) + lastPeriod * 60

private data class ResolvedSubject(val subjectName: String, val topics: List<String>)

private data class PlanContext(
    val resolvedSubjects: List<ResolvedSubject>,
    val levelTiers: Map<String, String>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>
)

class PlanGenerationService(
    private val llmClient: PlanGenerationLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher
) {

    suspend fun generate(userId: Int, request: PlanGenerationRequest): PlanGenerationResponse {
        if (request.title.isBlank() || request.title.length > 100) {
            throw PlanBoardValidationException.InvalidTitleException()
        }
        if (request.subjects.isEmpty()) {
            throw PlanBoardValidationException.SubjectsRequiredException()
        }

        val startDate = request.startDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() } }
            ?: LocalDate.now()
        val examDate = request.examDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() } }

        val endDate: LocalDate
        val totalDays: Int
        when {
            examDate != null -> {
                endDate = examDate.minusDays(1) // 시험 당일은 공부일에서 제외
                if (endDate.isBefore(startDate)) throw PlanBoardValidationException.InvalidDateRangeException()
                totalDays = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
            }
            request.daysRemaining != null && request.daysRemaining > 0 -> {
                totalDays = request.daysRemaining
                endDate = startDate.plusDays((totalDays - 1).toLong())
            }
            else -> throw PlanBoardValidationException.MissingDateInfoException()
        }

        val planContext = newSuspendedTransaction {
            val resolved = request.subjects.map { it to resolveSubject(it) }
            val tiers = resolved.map { (_, subject) -> subject.subjectName to levelTierFor(userId, subject.subjectName) }.toMap()
            val profileRow = StudentProfileTable.selectAll()
                .where { StudentProfileTable.user eq userId }
                .firstOrNull()
            val customRows = UnavailableTimeTable.selectAll()
                .where { UnavailableTimeTable.user eq userId }
                .map {
                    it[UnavailableTimeTable.dayOfWeek].code.toInt() to
                        (PlanTaskScheduler.toMinutes(it[UnavailableTimeTable.startTime]) to PlanTaskScheduler.toMinutes(it[UnavailableTimeTable.endTime]))
                }

            PlanContext(
                resolvedSubjects = resolved.map { it.second },
                levelTiers = tiers,
                grade = profileRow?.get(StudentProfileTable.grade) ?: 2,
                schoolId = profileRow?.get(StudentProfileTable.schoolId),
                classNumber = profileRow?.get(StudentProfileTable.classNumber),
                customUnavailableRows = customRows
            )
        }
        val resolvedSubjects = planContext.resolvedSubjects
        val levelTiers = planContext.levelTiers
        val grade = planContext.grade

        val unavailableRanges = buildUnavailableRanges(
            startDate = startDate,
            endDate = endDate,
            schoolId = planContext.schoolId,
            classNumber = planContext.classNumber,
            grade = planContext.grade,
            customRows = planContext.customUnavailableRows
        )

        val context = buildString {
            appendLine("총 학습 기간: ${totalDays}일 (${startDate} ~ ${endDate})")
            appendLine("학년(중학교): $grade")
            request.targetScore?.let { appendLine("목표 점수: $it") }
            appendLine("벼락치기 모드: ${request.isCramMode}")
            appendLine("과목별 학습 범위:")
            resolvedSubjects.forEach { subject ->
                appendLine("- ${subject.subjectName} (실력 등급: ${levelTiers[subject.subjectName]}): ${subject.topics.joinToString(", ")}")
            }
        }

        val generated = llmClient.generatePlan(context)
        if (generated.daily_plans.isEmpty()) throw PlanBoardValidationException.GenerationFailedException()

        return newSuspendedTransaction {
            val createdPlanBoardId = PlanBoardTable.insert {
                it[this.userId] = userId
                it[title] = request.title
                it[this.startDate] = startDate
                it[this.endDate] = endDate
                it[this.examDate] = examDate
                it[isCramMode] = request.isCramMode
                it[createdAt] = OffsetDateTime.now()
            } get PlanBoardTable.id

            request.subjects.forEach { subject ->
                PlanSubjects.insert {
                    it[this.planBoardId] = createdPlanBoardId.value
                    it[textbookId] = subject.textbookId
                    it[startChapterId] = subject.startChapterId
                    it[endChapterId] = subject.endChapterId
                    it[customRangeText] = subject.customRangeText
                }
            }

            val dailyResponses = generated.daily_plans
                .filter { it.day in 1..totalDays }
                .sortedBy { it.day }
                .map { day ->
                    val date = startDate.plusDays((day.day - 1).toLong())
                    val dailyPlanId = DailyPlanTable.insert {
                        it[this.planBoardId] = createdPlanBoardId.value
                        it[planDate] = date
                    } get DailyPlanTable.id

                    val freeIntervals = PlanTaskScheduler.freeIntervalsFromBusyRanges(unavailableRanges[date].orEmpty())
                    val placedTasks = PlanTaskScheduler.placeTasks(day.tasks.map { it.task_name to it.estimated_minutes }, freeIntervals)

                    placedTasks.forEach { task ->
                        PlanTaskTable.insert {
                            it[this.dailyPlanId] = dailyPlanId.value
                            it[taskName] = task.taskName
                            it[startTime] = task.startTime
                            it[endTime] = task.endTime
                            it[estimatedMinutes] = task.estimatedMinutes
                        }
                    }

                    PlanGenerationDailyResponse(
                        dailyPlanId = dailyPlanId.value,
                        date = date.toString(),
                        topics = day.topics,
                        goal = day.goal,
                        tasks = placedTasks.map {
                            PlanGenerationTaskResponse(
                                taskName = it.taskName,
                                estimatedMinutes = it.estimatedMinutes,
                                startTime = it.startTime.toString(),
                                endTime = it.endTime.toString()
                            )
                        }
                    )
                }

            PlanGenerationResponse(
                planBoardId = createdPlanBoardId.value,
                title = request.title,
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                examDate = examDate?.toString(),
                isCramMode = request.isCramMode,
                dailyPlans = dailyResponses,
                tips = generated.tips
            )
        }
    }

    private fun resolveSubject(input: PlanGenerationSubjectInput): ResolvedSubject {
        val textbookRow = Textbooks.selectAll().where { Textbooks.id eq input.textbookId }.firstOrNull()
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subjectName = Subjects.selectAll()
            .where { Subjects.id eq textbookRow[Textbooks.subjectId] }
            .firstOrNull()
            ?.get(Subjects.name)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val startOrder = Chapters.selectAll().where { Chapters.id eq input.startChapterId }.firstOrNull()
            ?.get(Chapters.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val endOrder = Chapters.selectAll().where { Chapters.id eq input.endChapterId }.firstOrNull()
            ?.get(Chapters.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        if (startOrder > endOrder) throw PlanBoardValidationException.InvalidSubjectRangeException()

        val topics = Chapters.selectAll()
            .where { Chapters.textbookId eq input.textbookId }
            .orderBy(Chapters.chapterOrder to SortOrder.ASC)
            .map { it[Chapters.chapterName] to it[Chapters.chapterOrder] }
            .filter { (_, order) -> order in startOrder..endOrder }
            .map { (name, _) -> name }

        val allTopics = if (input.customRangeText.isNullOrBlank()) topics else topics + input.customRangeText

        return ResolvedSubject(subjectName, allTopics)
    }

    private fun levelTierFor(userId: Int, subjectName: String): String {
        val subjectId = Subjects.selectAll().where { Subjects.name eq subjectName }.firstOrNull()?.get(Subjects.id)
            ?: return "중"
        val score = LevelTestResults.selectAll()
            .where { (LevelTestResults.userId eq userId) and (LevelTestResults.subjectId eq subjectId) }
            .orderBy(LevelTestResults.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.get(LevelTestResults.score)
            ?: return "중"

        return when {
            score >= 80 -> "상"
            score >= 40 -> "중"
            else -> "하"
        }
    }

    /**
     * 날짜별 학습 불가 시간대를 만든다.
     * 사용자가 직접 등록한 시간대(customRows)가 있으면 그것만 쓰고(기존 동작 유지),
     * 없으면 취침시간 기본값 + 평일 학교시간을 채우는데, 학교시간은 NICE 실시간 시간표로
     * 그날의 마지막 교시를 조회해 하교시각을 계산한다 (실패/데이터없음 시 기존 기본값으로 폴백).
     */
    private suspend fun buildUnavailableRanges(
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        customRows: List<Pair<Int, Pair<Int, Int>>>
    ): Map<LocalDate, List<Pair<Int, Int>>> {
        val dates = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()

        if (customRows.isNotEmpty()) {
            val byWeekday = customRows.groupBy({ it.first }, { it.second })
            return dates.associateWith { date -> byWeekday[date.dayOfWeek.value].orEmpty() }
        }

        val dismissalMinutesByDate = if (schoolId != null) {
            runCatching { fetchDismissalMinutesByDate(schoolId, classNumber, grade, startDate, endDate) }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }

        return dates.associateWith { date ->
            val ranges = DEFAULT_UNAVAILABLE_RANGES.toMutableList()
            if (date.dayOfWeek.value <= 5) { // 평일(월~금)만 등교 준비 + 학교시간 추가
                ranges.add(SCHOOL_PREP_RANGE)
                val schoolHours = dismissalMinutesByDate[date]?.let { SCHOOL_START_MINUTES to it } ?: DEFAULT_SCHOOL_HOURS
                ranges.add(schoolHours)
            }
            ranges
        }
    }

    /** NICE 시간표에서 날짜별 마지막 교시를 찾아 하교시각(분)으로 변환한다. 학기가 바뀌는 기간이면 학기별로 나눠 조회한다. */
    private suspend fun fetchDismissalMinutesByDate(
        schoolId: String,
        classNumber: Int?,
        grade: Int,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Int> {
        val className = classNumber?.toString()
        val semesters = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .map { academicYearAndSemester(it) }
            .distinct()

        val lastPeriodByDate = mutableMapOf<LocalDate, Int>()
        for ((year, semester) in semesters) {
            schoolDataFetcher.getTimetable(schoolId, year, semester, grade, className).forEach { entry ->
                if (entry.period > (lastPeriodByDate[entry.date] ?: 0)) {
                    lastPeriodByDate[entry.date] = entry.period
                }
            }
        }

        return lastPeriodByDate.mapValues { (_, lastPeriod) -> dismissalMinutesForLastPeriod(lastPeriod) }
    }

    /** 3~8월은 1학기, 9~2월은 2학기. 학년도(AY)는 학년도가 시작하는 연도(3월 기준) 기준. */
    private fun academicYearAndSemester(date: LocalDate): Pair<Int, Int> {
        val academicYear = if (date.monthValue >= 3) date.year else date.year - 1
        val semester = if (date.monthValue in 3..8) 1 else 2
        return academicYear to semester
    }
}
