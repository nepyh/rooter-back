package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationDailyResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationSubjectInput
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

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
            val profileRow = StudentProfileRow.find { StudentProfileTable.user eq userId }
                .firstOrNull()
            val customRows = UnavailableTimeRow.find { UnavailableTimeTable.user eq userId }
                .map {
                    it.dayOfWeek.code.toInt() to
                        (PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime))
                }

            PlanContext(
                resolvedSubjects = resolved.map { it.second },
                levelTiers = tiers,
                grade = profileRow?.grade ?: 2,
                schoolId = profileRow?.schoolId,
                classNumber = profileRow?.classNumber,
                customUnavailableRows = customRows
            )
        }
        val resolvedSubjects = planContext.resolvedSubjects
        val levelTiers = planContext.levelTiers
        val grade = planContext.grade

        val unavailableRanges = PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
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
            val board = PlanBoardRow.new {
                user = UserRow[userId]
                title = request.title
                this.startDate = startDate
                this.endDate = endDate
                this.examDate = examDate
                isCramMode = request.isCramMode
                createdAt = OffsetDateTime.now()
            }

            request.subjects.forEach { subject ->
                PlanSubjectRow.new {
                    planBoard = board
                    textbook = TextbookRow[subject.textbookId]
                    startChapter = ChapterRow[subject.startChapterId]
                    endChapter = ChapterRow[subject.endChapterId]
                    customRangeText = subject.customRangeText
                }
            }

            val dailyResponses = generated.daily_plans
                .filter { it.day in 1..totalDays }
                .sortedBy { it.day }
                .map { day ->
                    val date = startDate.plusDays((day.day - 1).toLong())
                    val dailyPlan = DailyPlanRow.new {
                        planBoard = board
                        planDate = date
                    }

                    val freeIntervals = PlanTaskScheduler.freeIntervalsFromBusyRanges(unavailableRanges[date].orEmpty())
                    val placedTasks = PlanTaskScheduler.placeTasks(day.tasks.map { it.task_name to it.estimated_minutes }, freeIntervals)

                    placedTasks.forEach { task ->
                        PlanTaskRow.new {
                            this.dailyPlan = dailyPlan
                            taskName = task.taskName
                            startTime = task.startTime
                            endTime = task.endTime
                            estimatedMinutes = task.estimatedMinutes
                        }
                    }

                    PlanGenerationDailyResponse(
                        dailyPlanId = dailyPlan.id.value,
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
                planBoardId = board.id.value,
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
        val textbook = TextbookRow.findById(input.textbookId)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subjectName = SubjectRow.findById(textbook.subject.id.value)?.name
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val startChapter = ChapterRow.findById(input.startChapterId)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val endChapter = ChapterRow.findById(input.endChapterId)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        if (startChapter.chapterOrder > endChapter.chapterOrder) {
            throw PlanBoardValidationException.InvalidSubjectRangeException()
        }

        val topics = ChapterRow.find { ChapterTable.textbookId eq input.textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .filter { it.chapterOrder in startChapter.chapterOrder..endChapter.chapterOrder }
            .map { it.chapterName }

        val allTopics = if (input.customRangeText.isNullOrBlank()) topics else topics + input.customRangeText

        return ResolvedSubject(subjectName, allTopics)
    }

    private fun levelTierFor(userId: Int, subjectName: String): String {
        val subject = SubjectRow.find { SubjectTable.name eq subjectName }.firstOrNull()
            ?: return "중"
        val score = LevelTestResultRow.find {
            (LevelTestResultTable.userId eq userId) and (LevelTestResultTable.subjectId eq subject.id)
        }
            .orderBy(LevelTestResultTable.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.score
            ?: return "중"

        return when {
            score >= 80 -> "상"
            score >= 40 -> "중"
            else -> "하"
        }
    }

}
