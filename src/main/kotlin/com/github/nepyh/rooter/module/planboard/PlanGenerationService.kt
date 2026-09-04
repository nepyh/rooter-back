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
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private const val DAY_MINUTES = 24 * 60
private val DEFAULT_UNAVAILABLE_RANGES = listOf(0 to (6 * 60 + 30), (23 * 60) to DAY_MINUTES) // 00:00~06:30, 23:00~24:00
private val DEFAULT_SCHOOL_HOURS = (8 * 60 + 30) to (16 * 60 + 30) // 08:30~16:30, 평일만
private const val DEFAULT_BREAK_MINUTES = 10

private data class ResolvedSubject(val subjectName: String, val topics: List<String>)

class PlanGenerationService(
    private val llmClient: PlanGenerationLlmClient
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

        val (resolvedSubjects, levelTiers, grade, unavailableRanges) = newSuspendedTransaction {
            val resolved = request.subjects.map { it to resolveSubject(it) }
            val tiers = resolved.map { (_, subject) -> subject.subjectName to levelTierFor(userId, subject.subjectName) }.toMap()
            val grade = StudentProfileTable.selectAll()
                .where { StudentProfileTable.user eq userId }
                .firstOrNull()
                ?.get(StudentProfileTable.grade)
                ?: 2
            val unavailable = loadUnavailableRanges(userId)
            Quadruple(resolved.map { it.second }, tiers, grade, unavailable)
        }

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

                    val freeIntervals = freeIntervalsForDate(date, unavailableRanges)
                    val tasks = placeTasks(day.tasks.map { it.task_name to it.estimated_minutes }, freeIntervals)

                    tasks.forEach { task ->
                        PlanTaskTable.insert {
                            it[this.dailyPlanId] = dailyPlanId.value
                            it[taskName] = task.taskName
                            it[startTime] = LocalTime.parse(task.startTime)
                            it[endTime] = LocalTime.parse(task.endTime)
                            it[estimatedMinutes] = task.estimatedMinutes
                        }
                    }

                    PlanGenerationDailyResponse(
                        dailyPlanId = dailyPlanId.value,
                        date = date.toString(),
                        topics = day.topics,
                        goal = day.goal,
                        tasks = tasks
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

    private fun loadUnavailableRanges(userId: Int): Map<Int, List<Pair<Int, Int>>> {
        val rows = UnavailableTimeTable.selectAll()
            .where { UnavailableTimeTable.user eq userId }
            .map { it[UnavailableTimeTable.dayOfWeek] to (toMinutes(it[UnavailableTimeTable.startTime]) to toMinutes(it[UnavailableTimeTable.endTime])) }

        if (rows.isEmpty()) {
            return DayOfWeek.entries.associate { day ->
                val ranges = DEFAULT_UNAVAILABLE_RANGES.toMutableList()
                if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) ranges.add(DEFAULT_SCHOOL_HOURS)
                day.code.toInt() to ranges
            }
        }

        return rows.groupBy({ it.first.code.toInt() }, { it.second })
    }

    private fun toMinutes(time: LocalTime): Int = time.hour * 60 + time.minute

    private fun freeIntervalsForDate(date: LocalDate, unavailableByDay: Map<Int, List<Pair<Int, Int>>>): List<Pair<Int, Int>> {
        val dayOfWeek = date.dayOfWeek.value // 1=월 ... 7=일
        val busy = unavailableByDay[dayOfWeek].orEmpty().sortedBy { it.first }

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((start, end) in busy) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, end)
            } else {
                merged.add(start to end)
            }
        }

        val free = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        for ((start, end) in merged) {
            if (start > cursor) free.add(cursor to start)
            cursor = maxOf(cursor, end)
        }
        if (cursor < DAY_MINUTES) free.add(cursor to DAY_MINUTES)
        return free
    }

    private fun placeTasks(items: List<Pair<String, Int>>, freeIntervals: List<Pair<Int, Int>>): List<PlanGenerationTaskResponse> {
        if (items.isEmpty() || freeIntervals.isEmpty()) return emptyList()

        var intervalIndex = 0
        var cursor = freeIntervals.getOrNull(0)?.first ?: 0

        val result = mutableListOf<PlanGenerationTaskResponse>()
        for ((taskName, minutes) in items) {
            while (intervalIndex < freeIntervals.size && cursor + minutes > freeIntervals[intervalIndex].second) {
                intervalIndex++
                cursor = freeIntervals.getOrNull(intervalIndex)?.first ?: cursor
            }
            if (intervalIndex >= freeIntervals.size) break // 남은 빈 시간이 없으면 이후 task는 배치하지 않음

            val start = cursor
            val end = start + minutes
            cursor = end + DEFAULT_BREAK_MINUTES
            result.add(
                PlanGenerationTaskResponse(
                    taskName = taskName.take(150),
                    estimatedMinutes = minutes,
                    startTime = formatMinutes(start),
                    endTime = formatMinutes(end)
                )
            )
        }
        return result
    }

    private fun formatMinutes(totalMinutes: Int): String {
        val clamped = totalMinutes.coerceIn(0, DAY_MINUTES - 1)
        return LocalTime.of(clamped / 60, clamped % 60).toString()
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
