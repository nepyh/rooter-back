package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationDailyResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationSubjectInput
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
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
                PlanSubjectTable.insert {
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
        val textbookRow = TextbookTable.selectAll().where { TextbookTable.id eq input.textbookId }.firstOrNull()
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subjectName = SubjectTable.selectAll()
            .where { SubjectTable.id eq textbookRow[TextbookTable.subjectId] }
            .firstOrNull()
            ?.get(SubjectTable.name)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val startOrder = ChapterTable.selectAll().where { ChapterTable.id eq input.startChapterId }.firstOrNull()
            ?.get(ChapterTable.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val endOrder = ChapterTable.selectAll().where { ChapterTable.id eq input.endChapterId }.firstOrNull()
            ?.get(ChapterTable.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        if (startOrder > endOrder) throw PlanBoardValidationException.InvalidSubjectRangeException()

        val topics = ChapterTable.selectAll()
            .where { ChapterTable.textbookId eq input.textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .map { it[ChapterTable.chapterName] to it[ChapterTable.chapterOrder] }
            .filter { (_, order) -> order in startOrder..endOrder }
            .map { (name, _) -> name }

        val allTopics = if (input.customRangeText.isNullOrBlank()) topics else topics + input.customRangeText

        return ResolvedSubject(subjectName, allTopics)
    }

    private fun levelTierFor(userId: Int, subjectName: String): String {
        val subjectId = SubjectTable.selectAll().where { SubjectTable.name eq subjectName }.firstOrNull()?.get(SubjectTable.id)
            ?: return "중"
        val score = LevelTestResultTable.selectAll()
            .where { (LevelTestResultTable.userId eq userId) and (LevelTestResultTable.subjectId eq subjectId) }
            .orderBy(LevelTestResultTable.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.get(LevelTestResultTable.score)
            ?: return "중"

        return when {
            score >= 80 -> "상"
            score >= 40 -> "중"
            else -> "하"
        }
    }

}
