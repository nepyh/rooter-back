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
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRetryResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanGenerationRequestTable
import com.github.nepyh.rooter.module.planboard.model.PlanGenerationStatus
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskChapterTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskOrigin
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.StudyStyleProfile
import com.github.nepyh.rooter.module.studystyle.userStudyStyle
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** [chapterIdsByName]: 학습 범위 단원 이름 → 단원 id. AI 가 할일마다 고른 단원 이름을 id 로 바꿀 때 쓴다 (직접 적은 범위는 단원이 아니라 없음) */
private data class ResolvedSubject(
    val subjectId: Int,
    val subjectName: String,
    val topics: List<String>,
    val chapterIdsByName: Map<String, Int> = emptyMap()
)

private data class PlanContext(
    val resolvedSubjects: List<ResolvedSubject>,
    val levelTiers: Map<String, String>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val routine: UserRoutine,
    val existingTaskRanges: Map<LocalDate, List<Pair<Int, Int>>>,
    val studyStyle: StudyStyleProfile
)

/** 구간 하나를 AI 로 만든 결과. [days] 의 day 는 전체 계획 기준(1부터) */
private class ChunkPlan(
    val days: List<GeneratedDailyPlan>,
    val tips: List<String>,
    val resolvedSubjects: List<ResolvedSubject>,
    val unavailableRanges: Map<LocalDate, List<Pair<Int, Int>>>,
    val placementByDate: Map<LocalDate, PlacementOptions>
)

/** 이어서 만들 보드 상태 (트랜잭션 밖으로 꺼낸 값) */
private class ContinueState(
    val userId: Int,
    val request: PlanGenerationRequest,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val generatedUntil: LocalDate
)

/**
 * AI 계획 생성 (#405): 앞 구간([CHUNK_DAYS]일 안팎)만 먼저 만들어 응답하고, 나머지 구간은 [continueGeneration] 이 하나씩 이어서 만든다.
 * 한 번에 다 만들면 기간이 길거나 과목이 많을 때 재시도 한 번에 ALB·앱 타임아웃(60초)을 넘겼다 (#400).
 *
 * @param onGenerating 앞 구간을 저장한 보드에 남은 구간이 있을 때 부른다 — 운영은 바로 이어서 만들기 시작한다 ([PlanGenerationContinuer]).
 *                     서버가 재시작돼도 [PlanGenerationContinueJob] 이 이어 만든다
 * @param notificationService 남은 구간까지 다 만들거나 실패했을 때 알림. 없으면 보내지 않는다 (테스트)
 */
class PlanGenerationService(
    private val llmClient: PlanGenerationLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher,
    private val clock: Clock = Clock.systemUTC(),
    private val notificationService: NotificationService? = null,
    var onGenerating: (Int) -> Unit = {}
) {
    private val logger = LoggerFactory.getLogger(PlanGenerationService::class.java)

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
        // 시험 마지막 날은 저장·표시용 — 계획 기간은 아래처럼 시험 첫날 전날까지 그대로다 (#376)
        val examEndDate = request.examEndDate?.let { parseBoardDate(it) }
        requireValidExamPeriod(examDate, examEndDate)

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
        val today = todayInAppZone(clock)
        requireStartNotPast(startDate, today)
        requireExamWithinLimit(examDate, today)
        requirePlanLengthWithinLimit(startDate, endDate, today)

        val chunks = splitIntoChunks(totalDays)
        val startedAt = System.nanoTime()
        val first = generateChunkPlan(userId, request, startDate, endDate, chunks, 0)
        val firstEnd = startDate.plusDays((chunks[0].last - 1).toLong())
        val generating = chunks.size > 1
        logger.info("AI 계획 앞 구간 생성: userId={} days={} chunks={} {}ms", userId, totalDays, chunks.size, (System.nanoTime() - startedAt) / 1_000_000)

        val response = newSuspendedTransaction {
            val board = PlanBoardRow.new {
                user = UserRow[userId]
                title = request.title
                this.startDate = startDate
                this.endDate = endDate
                this.examDate = examDate
                this.examEndDate = examEndDate
                isCramMode = request.isCramMode
                createdAt = OffsetDateTime.now()
                generationStatus = (if (generating) PlanGenerationStatus.GENERATING else PlanGenerationStatus.COMPLETED).value
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

            val dailyResponses = saveDays(board, startDate, first)

            if (generating) {
                PlanGenerationRequestTable.insert {
                    it[planBoardId] = board.id
                    it[PlanGenerationRequestTable.request] = requestJson.encodeToString(PlanGenerationRequest.serializer(), request)
                    it[generatedUntil] = firstEnd
                }
            }

            PlanGenerationResponse(
                planBoardId = board.id.value,
                title = request.title,
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                examDate = examDate?.toString(),
                examEndDate = examEndDate?.toString(),
                isCramMode = request.isCramMode,
                dailyPlans = dailyResponses,
                tips = first.tips.distinct().take(MAX_TIPS),
                generationStatus = (if (generating) PlanGenerationStatus.GENERATING else PlanGenerationStatus.COMPLETED).name,
                generatedUntil = (if (generating) firstEnd else endDate).toString()
            )
        }
        if (generating) onGenerating(response.planBoardId)
        return response
    }

    /** 이어서 만들 다음 구간의 시작일 (지금까지 만든 마지막 날의 다음 날) */
    fun nextChunkStart(generatedUntil: LocalDate): LocalDate = generatedUntil.plusDays(1)

    /**
     * 생성 중인 보드의 다음 구간 하나를 만들어 저장한다 (#405). 마지막 구간이면 보드를 완료로 바꾸고 알림을 보낸다.
     * 실패하면 예외를 그대로 던진다 — 호출한 쪽(스케줄러 잡)이 재시도하고, 재시도가 끝나면 [markFailed] 를 부른다.
     *
     * @param expectedStart 만들 구간의 시작일. 그 사이 다른 서버가 이미 만들었으면(생성 위치가 다르면) 아무것도 하지 않는다
     * @return 이 호출 뒤에도 남은 구간이 있으면 true
     */
    suspend fun continueGeneration(boardId: Int, expectedStart: LocalDate? = null): Boolean {
        val state = newSuspendedTransaction { loadContinueState(boardId) } ?: return false
        val start = nextChunkStart(state.generatedUntil)
        if (expectedStart != null && expectedStart != start) return false

        val totalDays = ChronoUnit.DAYS.between(state.startDate, state.endDate).toInt() + 1
        val chunks = splitIntoChunks(totalDays)
        val startDay = ChronoUnit.DAYS.between(state.startDate, start).toInt() + 1
        val index = chunks.indexOfFirst { it.first == startDay }
        if (index < 0) {
            // 구간 경계와 맞지 않는 위치 — 이미 끝까지 만들었거나 기간이 바뀐 경우. 남은 게 없으면 완료로 정리한다
            if (start.isAfter(state.endDate)) completeIfDone(boardId, state.userId)
            else logger.warn("AI 계획 이어 만들기: 구간 경계가 맞지 않음 boardId={} start={}", boardId, start)
            return false
        }

        val startedAt = System.nanoTime()
        val plan = generateChunkPlan(state.userId, state.request, state.startDate, state.endDate, chunks, index)
        val chunkEnd = state.startDate.plusDays((chunks[index].last - 1).toLong())
        val last = index == chunks.lastIndex

        val saved = newSuspendedTransaction {
            val board = PlanBoardRow.findById(boardId) ?: return@newSuspendedTransaction false
            val current = currentGeneratedUntil(boardId)
            // 그 사이 다른 곳에서 이 구간을 저장했거나 생성이 취소(보드 삭제·상태 변경)됐으면 저장하지 않는다
            if (board.generationStatus != PlanGenerationStatus.GENERATING.value || current != state.generatedUntil) {
                return@newSuspendedTransaction false
            }
            saveDays(board, state.startDate, plan)
            if (last) {
                board.generationStatus = PlanGenerationStatus.COMPLETED.value
                PlanGenerationRequestTable.deleteWhere { planBoardId eq boardId }
            } else {
                PlanGenerationRequestTable.update({ PlanGenerationRequestTable.planBoardId eq boardId }) {
                    it[generatedUntil] = chunkEnd
                    it[lastError] = null
                    it[updatedAt] = OffsetDateTime.now()
                }
            }
            true
        }
        logger.info(
            "AI 계획 이어 만들기: boardId={} 구간 {}/{} ({}~{}) {}ms saved={}",
            boardId, index + 1, chunks.size, start, chunkEnd, (System.nanoTime() - startedAt) / 1_000_000, saved
        )
        if (!saved) return false
        if (last) notify(state.userId, boardId, NotificationType.PLAN_GENERATION_COMPLETED, COMPLETED_TITLE, COMPLETED_BODY)
        return !last
    }

    /** 재시도까지 실패한 보드를 실패로 바꾸고 알린다. 앱은 다시 만들기([retry])를 안내한다 */
    suspend fun markFailed(boardId: Int, error: Throwable) {
        val userId = newSuspendedTransaction {
            val board = PlanBoardRow.findById(boardId) ?: return@newSuspendedTransaction null
            if (board.generationStatus != PlanGenerationStatus.GENERATING.value) return@newSuspendedTransaction null
            board.generationStatus = PlanGenerationStatus.FAILED.value
            PlanGenerationRequestTable.update({ PlanGenerationRequestTable.planBoardId eq boardId }) {
                it[lastError] = (error.message ?: error::class.simpleName)?.take(1000)
                it[updatedAt] = OffsetDateTime.now()
            }
            board.user.id.value
        } ?: return
        logger.warn("AI 계획 이어 만들기 실패로 확정: boardId={}", boardId, error)
        notify(userId, boardId, NotificationType.PLAN_GENERATION_FAILED, FAILED_TITLE, FAILED_BODY)
    }

    /**
     * 실패한 보드의 남은 구간을 다시 만들기 시작한다 (#405). 이미 만든 날은 그대로 두고 그 다음 날부터 이어서 만든다.
     * 본인 보드만, 실패(FAILED) 상태일 때만 — 아니면 409 PLAN_GENERATION_NOT_FAILED.
     */
    suspend fun retry(userId: Int, boardId: Int): PlanGenerationRetryResponse {
        val generatedUntil = newSuspendedTransaction {
            val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
            if (board.user.id.value != userId) throw PlanBoardForbiddenException()
            if (board.generationStatus != PlanGenerationStatus.FAILED.value) throw PlanBoardValidationException.GenerationNotFailedException()
            val until = currentGeneratedUntil(boardId) ?: throw PlanBoardValidationException.GenerationNotFailedException()
            board.generationStatus = PlanGenerationStatus.GENERATING.value
            // 재시도를 다 쓴 실행 기록을 지워야 스케줄러가 같은 구간(run_key)을 다시 잡는다
            JobRunTable.deleteWhere { (jobType eq PLAN_GENERATION_JOB_TYPE) and (runKey eq planGenerationRunKey(boardId, nextChunkStart(until))) }
            until
        }
        onGenerating(boardId)
        return PlanGenerationRetryResponse(boardId, PlanGenerationStatus.GENERATING.name, generatedUntil.toString())
    }

    private fun loadContinueState(boardId: Int): ContinueState? {
        val board = PlanBoardRow.findById(boardId) ?: return null
        if (board.generationStatus != PlanGenerationStatus.GENERATING.value) return null
        val row = PlanGenerationRequestTable.selectAll().where { PlanGenerationRequestTable.planBoardId eq boardId }.firstOrNull() ?: return null
        return ContinueState(
            userId = board.user.id.value,
            request = requestJson.decodeFromString(PlanGenerationRequest.serializer(), row[PlanGenerationRequestTable.request]),
            startDate = board.startDate,
            endDate = board.endDate,
            generatedUntil = row[PlanGenerationRequestTable.generatedUntil]
        )
    }

    private suspend fun completeIfDone(boardId: Int, userId: Int) {
        val completed = newSuspendedTransaction {
            val board = PlanBoardRow.findById(boardId) ?: return@newSuspendedTransaction false
            if (board.generationStatus != PlanGenerationStatus.GENERATING.value) return@newSuspendedTransaction false
            board.generationStatus = PlanGenerationStatus.COMPLETED.value
            PlanGenerationRequestTable.deleteWhere { planBoardId eq boardId }
            true
        }
        if (completed) notify(userId, boardId, NotificationType.PLAN_GENERATION_COMPLETED, COMPLETED_TITLE, COMPLETED_BODY)
    }

    private suspend fun notify(userId: Int, boardId: Int, type: NotificationType, title: String, body: String) {
        val service = notificationService ?: return
        runCatching { service.notify(userId, type, title, body, mapOf("planBoardId" to boardId.toString())) }
            .onFailure { logger.warn("AI 계획 생성 알림 실패: boardId={} type={}", boardId, type, it) }
    }

    /**
     * [chunks] 중 [index] 번째 구간을 AI 로 만든다. 빈 시간·기존 할일은 그 구간 날짜만, 지금 시점 기준으로 계산한다 —
     * 뒷 구간을 나중에 만들 때 그 사이 학생이 추가한 할일도 피한다.
     */
    private suspend fun generateChunkPlan(
        userId: Int,
        request: PlanGenerationRequest,
        startDate: LocalDate,
        endDate: LocalDate,
        chunks: List<IntRange>,
        index: Int
    ): ChunkPlan {
        val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        val days = chunks[index]
        val chunkStart = startDate.plusDays((days.first - 1).toLong())
        val chunkEnd = startDate.plusDays((days.last - 1).toLong())

        val planContext = newSuspendedTransaction {
            val resolved = request.subjects.map { it to resolveSubject(it) }
            val tiers = resolved.map { (_, subject) -> subject.subjectName to levelTierFor(userId, subject.subjectName) }.toMap()
            val profileRow = StudentProfileRow.find { StudentProfileTable.user eq userId }
                .firstOrNull()

            // 이미 있는 할일(다른 플랜보드 것 포함)과 겹치지 않게 배치하려고 구간 안의 할일 시간을 날짜별로 모은다
            val existingTaskRanges = userTaskRanges(userId, chunkStart, chunkEnd)

            PlanContext(
                resolvedSubjects = resolved.map { it.second },
                levelTiers = tiers,
                grade = profileRow?.grade ?: 2,
                schoolId = profileRow?.schoolId,
                classNumber = profileRow?.classNumber,
                routine = userRoutine(userId),
                existingTaskRanges = existingTaskRanges,
                studyStyle = userStudyStyle(userId)
            )
        }
        val resolvedSubjects = planContext.resolvedSubjects
        val levelTiers = planContext.levelTiers
        val grade = planContext.grade

        val typedUnavailableRanges = PlanTaskScheduler.buildTypedUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = chunkStart,
            endDate = chunkEnd,
            schoolId = planContext.schoolId,
            classNumber = planContext.classNumber,
            grade = planContext.grade,
            routine = planContext.routine
        )
        val baseUnavailableRanges = PlanTaskScheduler.withExistingTasks(
            typedUnavailableRanges.mapValues { (_, ranges) -> ranges.map { it.start to it.end } },
            planContext.existingTaskRanges
        )
        // 공부 스타일 설문에 따른 날짜별 배치 방식 (쉬는 시간, 밤부터 채우기, 피할 시간대)
        val placementByDate = typedUnavailableRanges.mapValues { (_, ranges) ->
            planContext.studyStyle.placementOptions(
                isSchoolDay = ranges.any { it.type == UnavailableType.SCHOOL },
                sleepTime = planContext.routine.sleepTime
            )
        }
        // 오늘이 든 구간이면 지금 이전 시간에는 배치하지 않는다 (챗봇 재조정과 같은 기준, 10분 단위 올림).
        // 예전엔 저녁에 만들어도 오늘 할일이 하교 시각(주말은 06:30)부터 잡혀 만들자마자 지난 할일이 됐다.
        val today = todayInAppZone(clock)
        val unavailableRanges = if (!today.isBefore(chunkStart) && !today.isAfter(chunkEnd)) {
            val now = LocalTime.now(clock.withZone(APP_ZONE))
            val earliestMinute = minOf(((now.hour * 60 + now.minute + 9) / 10) * 10, 24 * 60)
            baseUnavailableRanges + (today to baseUnavailableRanges[today].orEmpty() + (0 to earliestMinute))
        } else {
            baseUnavailableRanges
        }
        // AI 가 날짜마다 실제로 들어갈 수 있는 분량만 만들도록 그날 빈 시간을 알려준다.
        // 예전엔 오늘 시작하는 계획의 첫날만 알려줘서, 학원 있는 날처럼 빈 시간이 적은 날에 넘친 할일을 배치(placeTasks)가 조용히 버렸다
        val nowLabel = LocalTime.now(clock.withZone(APP_ZONE)).withSecond(0).withNano(0)
        fun capacityLine(dayNumber: Int, date: LocalDate): String {
            val free = PlanTaskScheduler.freeIntervalsFromBusyRanges(unavailableRanges[date].orEmpty())
            val total = free.sumOf { (start, end) -> end - start }
            val longest = free.maxOfOrNull { (start, end) -> end - start } ?: 0
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            val todayNote = if (date == today) ", 오늘이라 지금 $nowLabel 이후만" else ""
            // 공부 가능 시간만 주면 AI 가 그걸 기준 삼아 평일에도 5시간 넘게 채운다 → 하루 적정 분량(권장)을 함께 준다.
            // 등교일은 평일 기준, 주말·공휴일·방학은 쉬는 날 기준, 벼락치기는 1.5배. 공부 가능 시간보다 크게 주지는 않는다
            // 평소 공부량·주말 변수 설문은 이 권장 범위에 반영한다 (dailyTargetMinutes)
            val isSchoolDay = typedUnavailableRanges[date].orEmpty().any { it.type == UnavailableType.SCHOOL }
            val factor = if (request.isCramMode) CRAM_TARGET_FACTOR else 1.0
            fun scaled(range: IntRange) = (range.first * factor).toInt()..(range.last * factor).toInt()
            val target = planContext.studyStyle.dailyTargetMinutes(
                target = scaled(if (isSchoolDay) SCHOOL_DAY_TARGET_MINUTES else NO_SCHOOL_DAY_TARGET_MINUTES),
                weekdayTarget = scaled(SCHOOL_DAY_TARGET_MINUTES),
                planDay = ChronoUnit.DAYS.between(startDate, date).toInt() + 1,
                isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            )
            val low = minOf(target.first, total)
            val high = minOf(target.last, total)
            return "- day $dayNumber ($date $weekday$todayNote): 공부 가능 ${total}분, 가장 긴 연속 ${longest}분, 권장 ${low}~${high}분"
        }
        val breakMinutes = (placementByDate.values.firstOrNull() ?: PlacementOptions.DEFAULT).breakMinutes

        val context = buildString {
            appendLine("총 학습 기간: ${days.count()}일 (${chunkStart} ~ ${chunkEnd})")
            if (chunks.size > 1) {
                appendLine("전체 계획 ${totalDays}일 (${startDate} ~ ${endDate}) 중 ${days.first}~${days.last}일차 구간이다. day 는 이 구간 안에서 1부터 ${days.count()}까지 매겨라.")
            }
            appendLine("날짜별 공부 가능 시간 (수면·등교·학원 등 불가능 시간·다른 할일을 뺀 빈 시간, 할일 사이에는 쉬는 시간 ${breakMinutes}분이 들어간다):")
            days.forEachIndexed { dayIndex, day ->
                appendLine(capacityLine(dayIndex + 1, startDate.plusDays((day - 1).toLong())))
            }
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
        // 주말이 변수인 학생에게 이 구간의 토·일요일 day 번호를 알려준다
        val weekendDays = days.mapIndexedNotNull { dayIndex, day ->
            val date = startDate.plusDays((day - 1).toLong())
            if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) dayIndex + 1 else null
        }
        val generated = generateChunk(context, planContext.studyStyle.planRules(weekendDays), days.count())
        // 구간의 day(1부터) 를 전체 기준 day 로 옮긴다
        val offset = days.first - 1
        return ChunkPlan(
            days = generated.daily_plans.map { it.copy(day = it.day + offset) },
            tips = generated.tips,
            resolvedSubjects = resolvedSubjects,
            unavailableRanges = unavailableRanges,
            placementByDate = placementByDate
        )
    }

    /** 구간의 날짜별 할일을 배치해 저장한다. 트랜잭션 안에서 부른다. 이미 있는 그날 계획(일일 계획 행)이 있으면 거기에 붙인다 */
    private fun saveDays(board: PlanBoardRow, startDate: LocalDate, plan: ChunkPlan): List<PlanGenerationDailyResponse> {
        val resolvedSubjects = plan.resolvedSubjects
        // LLM 이 준 task 의 subject_name 을 요청한 과목명과 맞춰 볼 이름 → 과목 id 맵 (앞뒤 공백은 무시하고 정확히 일치할 때만)
        val subjectIdsByName = resolvedSubjects.associate { it.subjectName.trim() to it.subjectId }
        val subjectNamesById = resolvedSubjects.associate { it.subjectId to it.subjectName }
        // 과목 id → 그 과목 범위의 단원 이름 → 단원 id. 같은 과목을 두 번 요청했으면 범위를 합친다
        val chapterIdsBySubject = resolvedSubjects.groupBy { it.subjectId }
            .mapValues { (_, subjects) -> subjects.fold(emptyMap<String, Int>()) { acc, subject -> subject.chapterIdsByName + acc } }

        return plan.days
            .sortedBy { it.day }
            .map { day ->
                val date = startDate.plusDays((day.day - 1).toLong())
                val dailyPlan = DailyPlanRow.find { (DailyPlanTable.planBoardId eq board.id) and (DailyPlanTable.planDate eq date) }.firstOrNull()
                    ?: DailyPlanRow.new {
                        planBoard = board
                        planDate = date
                    }

                val freeIntervals = PlanTaskScheduler.freeIntervalsFromBusyRanges(plan.unavailableRanges[date].orEmpty())
                val placedTasks = PlanTaskScheduler.placeTasks(
                    day.tasks.map { it.task_name to it.estimated_minutes },
                    freeIntervals,
                    plan.placementByDate[date] ?: PlacementOptions.DEFAULT
                )

                // placeTasks 는 자리가 없으면 뒤쪽 태스크를 빼므로(배치 옵션과 무관) 결과는 입력의 앞부분과 순서대로 1:1 로 맞는다.
                // LLM 이 준 과목명이 요청한 과목명과 맞을 때만 붙인다.
                // 못 맞추면 null 로 두고 저장은 계속한다 (과목을 못 붙였다고 생성을 실패시키지 않음)
                val taskSubjectIds = placedTasks.indices.map { index ->
                    day.tasks.getOrNull(index)?.subject_name?.trim()?.let { subjectIdsByName[it] }
                }
                placedTasks.zip(taskSubjectIds).forEachIndexed { index, (task, taskSubjectId) ->
                    val row = PlanTaskRow.new {
                        this.dailyPlan = dailyPlan
                        taskName = task.taskName
                        startTime = task.startTime
                        endTime = task.endTime
                        estimatedMinutes = task.estimatedMinutes
                        this.subjectId = taskSubjectId?.let { EntityID(it, SubjectTable) }
                        // AI 가 만든 계획의 할일 — 사용자가 지울 수 없다 (#245)
                        origin = PlanTaskOrigin.AI
                    }
                    // AI 가 고른 단원 중 그 할일 과목의 범위에 있는 것만 저장한다. 과목을 못 맞췄으면 단원도 저장하지 않는다 (#387)
                    val chapterIds = taskSubjectId?.let { subjectId ->
                        val byName = chapterIdsBySubject[subjectId].orEmpty()
                        day.tasks.getOrNull(index)?.chapter_names.orEmpty().mapNotNull { byName[it.trim()] }.distinct()
                    }.orEmpty()
                    PlanTaskChapterTable.batchInsert(chapterIds, shouldReturnGeneratedValues = false) { chapterId ->
                        this[PlanTaskChapterTable.planTaskId] = row.id
                        this[PlanTaskChapterTable.chapterId] = EntityID(chapterId, ChapterTable)
                    }
                }

                PlanGenerationDailyResponse(
                    dailyPlanId = dailyPlan.id.value,
                    date = date.toString(),
                    topics = day.topics,
                    goal = day.goal,
                    tasks = placedTasks.zip(taskSubjectIds) { task, taskSubjectId ->
                        PlanGenerationTaskResponse(
                            taskName = task.taskName,
                            estimatedMinutes = task.estimatedMinutes,
                            startTime = task.startTime.toString(),
                            endTime = task.endTime.toString(),
                            subjectId = taskSubjectId,
                            subjectName = taskSubjectId?.let { subjectNamesById[it] }
                        )
                    }
                )
            }
    }

    // 한 구간을 생성하고, 요청한 일수만큼 빠짐없이 왔는지 확인한다.
    // 모자라면 한 번 더 요청하고, 그래도 모자라면 일부만 저장하지 않고 실패로 끝낸다.
    private suspend fun generateChunk(context: String, studyStyleRules: String, chunkDays: Int): GeneratedPlan {
        repeat(MAX_ATTEMPTS) {
            val plan = runCatching { llmClient.generatePlan(context, studyStyleRules) }
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
        val subjectRow = SubjectRow.findById(textbook.subject.id.value)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val (startChapter, endChapter) = resolveChapterRange(textbook, input.startChapterId, input.endChapterId)

        // 범위 선택은 검증과 같은 트리 순회 순서(대단원 → 소단원)를 쓴다.
        // 예전엔 chapter_order 만 비교해 소단원의 order 가 대단원 안에서 다시 1부터 시작하는 계층을 무시했다.
        val chapters = orderedChaptersInRange(textbook, startChapter, endChapter)
        val topics = chapters.map { it.chapterName }

        val allTopics = if (input.customRangeText.isNullOrBlank()) topics else topics + input.customRangeText

        // 같은 이름 단원이 범위에 둘 있으면 앞의 것 (이름만으로는 구분할 수 없음)
        val chapterIdsByName = chapters.reversed().associate { it.chapterName.trim() to it.id.value }

        return ResolvedSubject(subjectRow.id.value, subjectRow.name, allTopics, chapterIdsByName)
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

private const val CHUNK_DAYS = 7
private const val MAX_ATTEMPTS = 2
private const val MAX_TIPS = 5

private const val COMPLETED_TITLE = "계획이 모두 완성됐어요"
private const val COMPLETED_BODY = "시험 전날까지의 계획을 다 만들었어요. 지금 확인해 보세요."
private const val FAILED_TITLE = "계획을 끝까지 만들지 못했어요"
private const val FAILED_BODY = "남은 기간의 계획을 만들지 못했어요. 플랜보드에서 다시 만들기를 눌러주세요."

// 하루 권장 공부량(분) — 중학생 기준. 등교일 / 주말·공휴일·방학, 벼락치기 모드는 1.5배 (#304)
private val SCHOOL_DAY_TARGET_MINUTES = 90..150
private val NO_SCHOOL_DAY_TARGET_MINUTES = 150..240
private const val CRAM_TARGET_FACTOR = 1.5

// 구간을 나눠 앞 구간만 먼저 만들어 응답하고 나머지는 이어서 만든다 (#405). 구간이 짧을수록 앞 구간 응답이 빠르다.
// (LLM 은 한 번에 긴 기간을 요청하면 8~10일치만 돌려주고 끊기도 한다.) 7일을 넘으면 비슷한 길이로 나눈다 (예: 30일 → 6/6/6/6/6)
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

/** 이어서 만들기 스케줄러 잡의 job_type 과 run_key (구간 시작일마다 하나) */
const val PLAN_GENERATION_JOB_TYPE = "plan_generation_continue"

fun planGenerationRunKey(boardId: Int, chunkStart: LocalDate): String = "plan_generation_${boardId}_$chunkStart"

/** 생성 중·실패한 보드의 지금까지 만든 마지막 날짜. 트랜잭션 안에서 부른다 */
internal fun currentGeneratedUntil(boardId: Int): LocalDate? =
    PlanGenerationRequestTable.select(PlanGenerationRequestTable.generatedUntil)
        .where { PlanGenerationRequestTable.planBoardId eq boardId }
        .firstOrNull()
        ?.get(PlanGenerationRequestTable.generatedUntil)

/** 보드에 아직 계획이 만들어지지 않은 날이면 409 PLAN_GENERATION_IN_PROGRESS (#405). 트랜잭션 안에서 부른다 */
internal fun requireGeneratedDate(board: PlanBoardRow, date: LocalDate) {
    if (board.generationStatus == PlanGenerationStatus.COMPLETED.value) return
    val until = currentGeneratedUntil(board.id.value) ?: return
    if (date.isAfter(until)) throw PlanBoardValidationException.GenerationInProgressException()
}

/** 생성 중인 보드면 409 PLAN_GENERATION_IN_PROGRESS — 기간·과목처럼 이어 만들기 입력이 바뀌는 수정을 막는다 (#405) */
internal fun requireNotGenerating(board: PlanBoardRow) {
    if (board.generationStatus == PlanGenerationStatus.GENERATING.value) throw PlanBoardValidationException.GenerationInProgressException()
}

private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 계획이 만들어진 마지막 날. 다 만든 보드는 기간 끝, 남은 기간을 만드는 중·실패한 AI 계획은 만든 날까지 (#405).
 * 챗봇처럼 다른 날 계획을 보고 고치는 기능이 아직 안 만든 날을 건드리지 않게 쓴다. 트랜잭션 안에서 부른다
 */
fun generatedEndDateOf(board: PlanBoardRow): LocalDate =
    if (board.generationStatus == PlanGenerationStatus.COMPLETED.value) board.endDate
    else currentGeneratedUntil(board.id.value)?.let { minOf(it, board.endDate) } ?: board.endDate
