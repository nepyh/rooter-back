package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationDailyResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationSubjectInput
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private data class ResolvedSubject(val subjectName: String, val topics: List<String>)

private data class PlanContext(
    val resolvedSubjects: List<ResolvedSubject>,
    val levelTiers: Map<String, String>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>,
    val existingTaskRanges: Map<LocalDate, List<Pair<Int, Int>>>
)

class PlanGenerationService(
    private val llmClient: PlanGenerationLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher,
    private val clock: Clock = Clock.systemUTC()
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
            ?: todayInAppZone(clock)
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

            // 이미 있는 할일(다른 플랜보드 것 포함)과 겹치지 않게 배치하려고 기간 안의 할일 시간을 날짜별로 모은다
            val existingTaskRanges = userTaskRanges(userId, startDate, endDate)

            PlanContext(
                resolvedSubjects = resolved.map { it.second },
                levelTiers = tiers,
                grade = profileRow?.grade ?: 2,
                schoolId = profileRow?.schoolId,
                classNumber = profileRow?.classNumber,
                customUnavailableRows = customRows,
                existingTaskRanges = existingTaskRanges
            )
        }
        val resolvedSubjects = planContext.resolvedSubjects
        val levelTiers = planContext.levelTiers
        val grade = planContext.grade

        val baseUnavailableRanges = PlanTaskScheduler.withExistingTasks(
            PlanTaskScheduler.buildUnavailableRanges(
                schoolDataFetcher = schoolDataFetcher,
                startDate = startDate,
                endDate = endDate,
                schoolId = planContext.schoolId,
                classNumber = planContext.classNumber,
                grade = planContext.grade,
                customRows = planContext.customUnavailableRows
            ),
            planContext.existingTaskRanges
        )
        // 오늘부터 시작하는 계획이면 지금 이전 시간에는 배치하지 않는다 (챗봇 재조정과 같은 기준, 10분 단위 올림).
        // 예전엔 저녁에 만들어도 오늘 할일이 하교 시각(주말은 06:30)부터 잡혀 만들자마자 지난 할일이 됐다.
        val today = todayInAppZone(clock)
        val unavailableRanges = if (startDate == today) {
            val now = LocalTime.now(clock.withZone(APP_ZONE))
            val earliestMinute = minOf(((now.hour * 60 + now.minute + 9) / 10) * 10, 24 * 60)
            baseUnavailableRanges + (today to baseUnavailableRanges[today].orEmpty() + (0 to earliestMinute))
        } else {
            baseUnavailableRanges
        }
        // AI 가 첫날 분량을 남은 시간에 맞추도록 알려준다 (오늘 시작일 때만)
        val firstDayNote = if (startDate == today) {
            val freeMinutes = PlanTaskScheduler.freeIntervalsFromBusyRanges(unavailableRanges[today].orEmpty())
                .sumOf { (start, end) -> end - start }
            val nowLabel = LocalTime.now(clock.withZone(APP_ZONE)).withSecond(0).withNano(0)
            "첫날(${today})은 지금 ${nowLabel} 이후만 공부할 수 있고, 남은 공부 가능 시간은 약 ${freeMinutes}분이다. " +
                "첫날(day 1) tasks 의 estimated_minutes 합은 ${freeMinutes}분을 넘기지 마라" +
                if (freeMinutes < 30) " (남은 시간이 거의 없으면 첫날 tasks 는 비워도 된다)." else "."
        } else {
            null
        }

        val chunks = splitIntoChunks(totalDays)
        val generatedChunks = coroutineScope {
            val limiter = Semaphore(MAX_PARALLEL_CHUNKS)
            chunks.mapIndexed { index, days ->
                async {
                    limiter.withPermit {
                        val context = buildString {
                            val chunkStart = startDate.plusDays((days.first - 1).toLong())
                            val chunkEnd = startDate.plusDays((days.last - 1).toLong())
                            appendLine("총 학습 기간: ${days.count()}일 (${chunkStart} ~ ${chunkEnd})")
                            if (chunks.size > 1) {
                                appendLine("전체 계획 ${totalDays}일 (${startDate} ~ ${endDate}) 중 ${days.first}~${days.last}일차 구간이다. day 는 이 구간 안에서 1부터 ${days.count()}까지 매겨라.")
                            }
                            if (index == 0) firstDayNote?.let { appendLine(it) }
                            appendLine("학년(중학교): $grade")
                            request.targetScore?.let { appendLine("목표 점수: $it") }
                            appendLine("벼락치기 모드: ${request.isCramMode}")
                            appendLine(if (chunks.size > 1) "과목별 학습 범위(이 구간에서 다룰 부분):" else "과목별 학습 범위:")
                            resolvedSubjects.forEach { subject ->
                                val topics = topicsForChunk(subject.topics, days, totalDays)
                                appendLine("- ${subject.subjectName} (실력 등급: ${levelTiers[subject.subjectName]}): ${topics.joinToString(", ")}")
                            }
                            if (chunks.size > 1) {
                                appendLine("전체 시험 범위(참고용, 이 구간 밖의 단원은 새로 진도 나가지 마라):")
                                resolvedSubjects.forEach { subject ->
                                    appendLine("- ${subject.subjectName}: ${subject.topics.joinToString(", ")}")
                                }
                                if (index == chunks.lastIndex) appendLine("마지막 구간이므로 끝부분에 전체 범위 총복습을 넣어라.")
                            }
                        }
                        generateChunk(context, days.count())
                    }
                }
            }.awaitAll()
        }

        // 각 구간의 day(1부터) 를 전체 기준 day 로 옮겨 하나로 합친다.
        val generated = GeneratedPlan(
            daily_plans = generatedChunks.flatMapIndexed { index, chunk ->
                val offset = chunks[index].first - 1
                chunk.daily_plans.map { it.copy(day = it.day + offset) }
            },
            tips = generatedChunks.flatMap { it.tips }.distinct().take(MAX_TIPS)
        )

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

    // 한 구간을 생성하고, 요청한 일수만큼 빠짐없이 왔는지 확인한다.
    // 모자라면 한 번 더 요청하고, 그래도 모자라면 일부만 저장하지 않고 실패로 끝낸다.
    private suspend fun generateChunk(context: String, chunkDays: Int): GeneratedPlan {
        repeat(MAX_ATTEMPTS) {
            val plan = runCatching { llmClient.generatePlan(context) }
                .getOrElse { e -> if (e is PlanBoardValidationException.GenerationFailedException) null else throw e }
                ?: return@repeat
            val days = plan.daily_plans
                .filter { it.day in 1..chunkDays }
                .distinctBy { it.day }
            if (days.size == chunkDays) return plan.copy(daily_plans = days)
        }
        throw PlanBoardValidationException.GenerationFailedException()
    }

    private fun resolveSubject(input: PlanGenerationSubjectInput): ResolvedSubject {
        val textbook = TextbookRow.findById(input.textbookId)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subjectName = SubjectRow.findById(textbook.subject.id.value)?.name
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val (startChapter, endChapter) = resolveChapterRange(textbook, input.startChapterId, input.endChapterId)

        // 범위 선택은 검증과 같은 트리 순회 순서(대단원 → 소단원)를 쓴다.
        // 예전엔 chapter_order 만 비교해 소단원의 order 가 대단원 안에서 다시 1부터 시작하는 계층을 무시했다.
        val topics = orderedChaptersInRange(textbook, startChapter, endChapter).map { it.chapterName }

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

private const val CHUNK_DAYS = 14
private const val MAX_ATTEMPTS = 2
private const val MAX_PARALLEL_CHUNKS = 4
private const val MAX_TIPS = 5

// LLM 은 한 번에 긴 기간을 요청하면 8~10일치만 돌려주고 끊는다.
// 그래서 14일을 넘으면 비슷한 길이의 구간으로 나눠 따로 생성한다. (예: 30일 → 10/10/10)
internal fun splitIntoChunks(totalDays: Int): List<IntRange> {
    val count = (totalDays + CHUNK_DAYS - 1) / CHUNK_DAYS
    val base = totalDays / count
    val extra = totalDays % count
    var start = 1
    return (0 until count).map { i ->
        val size = base + if (i < extra) 1 else 0
        (start until start + size).also { start += size }
    }
}

// 구간이 전체 기간에서 차지하는 비율만큼 단원을 잘라 준다.
// 단원 수가 구간 수보다 적으면 여러 구간이 같은 단원을 나눠 가진다.
internal fun <T> topicsForChunk(topics: List<T>, days: IntRange, totalDays: Int): List<T> {
    if (topics.isEmpty()) return topics
    val from = ((days.first - 1).toLong() * topics.size / totalDays).toInt()
    val to = ((days.last.toLong() * topics.size + totalDays - 1) / totalDays).toInt()
    return topics.subList(from.coerceAtMost(topics.size - 1), to.coerceIn(from + 1, topics.size))
}
