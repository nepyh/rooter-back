package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.chat.dto.AiChatCurrentTask
import com.github.nepyh.rooter.module.chat.dto.AiChatPostpone
import com.github.nepyh.rooter.module.chat.dto.AiChatTurn
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.chat.model.ChatTurnRow
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.SleepTime
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.userSleepTime
import com.github.nepyh.rooter.module.planboard.userTaskRanges
import com.github.nepyh.rooter.module.planboard.isManualPlanBoard
import com.github.nepyh.rooter.module.planboard.subjectNameFrom
import com.github.nepyh.rooter.module.planboard.subjectNamesOf
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.userStudyStyle
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.chat.dto.AiChatEdit
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
import com.github.nepyh.rooter.module.taskquiz.MAX_ATTEMPTS
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 한 번의 챗봇 호출에 함께 실어 보내는 최근 대화 턴 수. 너무 길면 프롬프트가 불필요하게 커진다. */
private const val HISTORY_LIMIT = 10

/** AI 가 plan_changed=true 라고 했지만 적용할 변경이 없었을 때 대신 보여줄 답변 */
internal const val PLAN_NOT_APPLIED_REPLY = "계획을 바꾸려고 했는데 적용하지 못했어요. 오늘 계획은 그대로예요. 바꾸고 싶은 내용을 조금 더 자세히 말씀해 주세요."

class ChatService(
    private val chatLlmClient: ChatLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher,
    private val clock: Clock = Clock.systemUTC()
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 소유자 확인은 daily_plans + plan_boards 조인이 필요해 Table DSL 로 조회한 뒤 엔티티로 감싼다. */
    private fun requireOwnedDailyPlan(userId: Int, dailyPlanId: Int): DailyPlanRow =
        (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?.let { DailyPlanRow.wrapRow(it) }
            ?: throw DailyPlanNotFoundException()

    suspend fun sendMessage(userId: Int, dailyPlanId: Int, message: String): ChatMessageResponse {
        val trimmed = message.trim()
        if (trimmed.isBlank()) throw ChatValidationException.MessageRequiredException()

        val context = newSuspendedTransaction {
            val dailyPlanRow = requireOwnedDailyPlan(userId, dailyPlanId)
            val planDate = dailyPlanRow.planDate
            val board = dailyPlanRow.planBoard

            // 이미 완료한 태스크는 재조정 대상이 아니다 — AI 에는 미완료 태스크만 보내고,
            // 완료 태스크의 시간대는 재배치할 때 막아둔다.
            val (completedTasks, pendingTasks) = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .partition { it.isCompleted }

            val profileRow = StudentProfileRow.find { StudentProfileTable.user eq userId }
                .firstOrNull()
            val grade = profileRow?.grade ?: 2
            val schoolId = profileRow?.schoolId
            val classNumber = profileRow?.classNumber
            val customUnavailableRows = UnavailableTimeRow.find { UnavailableTimeTable.user eq userId }
                .map {
                    it.dayOfWeek.code.toInt() to
                        (PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime))
                }

            // 숫자(문항1=2)가 아니라 학생이 고른 보기 문구로 넘겨야 AI 가 뜻을 안다
            val studyStyleSummary = userStudyStyle(userId).summary()

            // 할일 직접 편집(edits)용 — 할일 과목, 퀴즈 진행·확정 여부, 플랜보드 과목·학습 범위, 같은 날 다른 보드의 할일
            val subjectNames = subjectNamesOf(pendingTasks)
            val quizState = quizStateOf(pendingTasks.map { it.id.value })
            val planSubjects = planSubjectsOf(board.id.value)
            val otherBoardRanges = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (PlanBoardTable.userId eq userId) and (DailyPlanTable.planDate eq planDate) and (DailyPlanTable.id neq dailyPlanId) }
                .map { PlanTaskScheduler.toMinutes(it[PlanTaskTable.startTime]) to PlanTaskScheduler.toMinutes(it[PlanTaskTable.endTime]) }

            val history = ChatTurnRow.find { ChatTurnTable.dailyPlanId eq dailyPlanId }
                .orderBy(ChatTurnTable.createdAt to SortOrder.ASC)
                .map { AiChatTurn(role = it.role, content = it.content) }
                .takeLast(HISTORY_LIMIT)

            ChatContext(
                planDate = planDate.toString(),
                dayOfWeekLabel = planDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN),
                pendingTasks = pendingTasks.map {
                    AiChatCurrentTask(
                        it.taskName, it.estimatedMinutes, it.startTime.toString(), it.endTime.toString(),
                        can_postpone = !postponedFromPreviousDay(it.postponedFromDate, planDate),
                        subject_name = it.subjectNameFrom(subjectNames)
                    )
                },
                editable = pendingTasks.map {
                    val state = quizState[it.id.value]
                    EditableTask(
                        id = it.id.value,
                        name = it.taskName,
                        start = PlanTaskScheduler.toMinutes(it.startTime),
                        end = PlanTaskScheduler.toMinutes(it.endTime),
                        subjectId = it.subjectId?.value,
                        quizStarted = state != null,
                        quizFinalized = state == true
                    )
                },
                otherBoardRanges = otherBoardRanges,
                planSubjectsText = planSubjects.joinToString("\n") { it.line },
                subjectIdsByName = planSubjects.associate { it.subjectName to it.subjectId },
                pendingExisting = pendingTasks.map {
                    ExistingPendingTask(
                        it.id.value, it.taskName,
                        PlanTaskScheduler.toMinutes(it.startTime), PlanTaskScheduler.toMinutes(it.endTime)
                    )
                },
                completedRanges = completedTasks.map {
                    PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime)
                },
                grade = grade,
                schoolId = schoolId,
                classNumber = classNumber,
                customUnavailableRows = customUnavailableRows,
                sleepTime = userSleepTime(userId),
                studyStyleSummary = studyStyleSummary,
                planBoardSummary = planBoardSummaryOf(board.title, board.startDate, board.endDate, board.examDate, planDate),
                history = history,
                planBoardId = board.id.value,
                planBoardIsManual = isManualPlanBoard(board.examDate),
                planBoardEndDate = board.endDate,
                pendingPostponedFrom = pendingTasks.associate { it.id.value to it.postponedFromDate }
            )
        }

        val result = chatLlmClient.adjustPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            targetDate = "${context.planDate} (${context.dayOfWeekLabel})",
            today = todayInAppZone(clock).let { "$it (${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)})" },
            currentTasksJson = json.encodeToString(context.pendingTasks),
            chatHistoryJson = json.encodeToString(context.history),
            planBoardSummary = context.planBoardSummary,
            userMessage = trimmed,
            planSubjects = context.planSubjectsText
        )

        // 다른 날로 미뤄 달라는 요청이면 그날 계획 재구성 대신 미루기만 한다
        result?.postpone?.takeIf { it.task_names.isNotEmpty() }?.let { postpone ->
            return postponeTasks(userId, dailyPlanId, trimmed, context, postpone, result.reply_message)
        }

        // 할일을 직접 줄이기·추가·삭제·옮기기 해 달라는 요청이면 그 동작만 검증해 적용한다 (#306)
        result?.edits?.takeIf { it.isNotEmpty() }?.let { edits ->
            return editTasks(dailyPlanId, trimmed, context, edits)
        }

        val update = result?.plan_update
        // AI 는 시각 비교를 자주 틀린다 (18:00~18:30 태스크가 19~21시와 겹친다고 판단하는 식).
        // 새로 생긴 공부 불가능 시간이 남은 태스크와 겹치는지는 서버가 직접 판단하고, 안 겹치면 계획을 건드리지 않는다.
        val busyWindow = update?.let { busyWindowOf(it.busy_window_start, it.busy_window_end) }
        val busyWindowOverlapsNothing = busyWindow != null && context.pendingTasks.none { overlaps(it, busyWindow) }
        val shouldReplan = result != null && result.plan_changed && update != null && update.tasks.isNotEmpty() &&
            !busyWindowOverlapsNothing

        // NICE 시간표 조회(네트워크 호출)가 있어 트랜잭션 밖에서 미리 계산해둔다.
        val replanBusyRanges = if (shouldReplan) {
            val planDate = LocalDate.parse(context.planDate)
            val busyRanges = PlanTaskScheduler.buildUnavailableRanges(
                schoolDataFetcher = schoolDataFetcher,
                startDate = planDate,
                endDate = planDate,
                schoolId = context.schoolId,
                classNumber = context.classNumber,
                grade = context.grade,
                customRows = context.customUnavailableRows,
                sleepTime = context.sleepTime
            )[planDate].orEmpty()
            val extraBusy = if (update!!.busy_window_start != null && update.busy_window_end != null) {
                listOf(
                    PlanTaskScheduler.toMinutes(LocalTime.parse(update.busy_window_start)) to
                        PlanTaskScheduler.toMinutes(LocalTime.parse(update.busy_window_end))
                )
            } else {
                emptyList()
            }
            busyRanges + extraBusy + context.completedRanges
        } else {
            emptyList()
        }
        // 오늘 계획이면 옮기는 태스크를 지난 시간에 넣지 않는다 (10분 단위로 올림)
        val earliestMinute = if (LocalDate.parse(context.planDate) == todayInAppZone(clock)) {
            val now = LocalTime.now(clock.withZone(APP_ZONE))
            ((now.hour * 60 + now.minute + 9) / 10) * 10
        } else {
            0
        }

        return newSuspendedTransaction {
            ChatTurnRow.new {
                this.dailyPlan = DailyPlanRow[dailyPlanId]
                role = "user"
                content = trimmed
                createdAt = OffsetDateTime.now()
            }

            if (result == null) {
                val fallback = "죄송해요, 지금은 답변을 드릴 수 없어요. 잠시 후 다시 시도해주세요."
                ChatTurnRow.new {
                    this.dailyPlan = DailyPlanRow[dailyPlanId]
                    role = "assistant"
                    content = fallback
                    createdAt = OffsetDateTime.now()
                }
                return@newSuspendedTransaction ChatMessageResponse(reply = fallback, planChanged = false)
            }

            var updatedTasks: List<PlanTaskResponse>? = null
            if (shouldReplan) {
                // 안 겹치는 태스크는 제자리(id·시각 유지), 겹치는 것만 옮긴다 — rescheduleKeepingUnaffected 참고
                val rescheduled = rescheduleKeepingUnaffected(
                    existing = context.pendingExisting,
                    aiTasks = update!!.tasks.map { it.task_name to it.estimated_minutes },
                    busyRanges = replanBusyRanges,
                    earliestMinute = earliestMinute
                )

                val dailyPlan = DailyPlanRow[dailyPlanId]
                val keptIds = rescheduled.mapNotNull { it.existingId }.toSet()
                // AI 가 뺐거나 자리가 없어 못 넣은 미완료 태스크만 지운다 (완료 태스크는 건드리지 않음)
                PlanTaskRow.find { (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.isCompleted eq false) }
                    .filter { it.id.value !in keptIds }
                    .forEach { it.delete() }
                rescheduled.forEach { task ->
                    val row = task.existingId?.let { PlanTaskRow.findById(it) } ?: PlanTaskRow.new { this.dailyPlan = dailyPlan }
                    row.taskName = task.taskName
                    row.startTime = task.startTime
                    row.endTime = task.endTime
                    row.estimatedMinutes = task.estimatedMinutes
                }

                val dayRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                    .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                    .toList()
                val subjectNames = subjectNamesOf(dayRows)
                updatedTasks = dayRows.map { it.toResponse(dailyPlanId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames)) }
            }

            // AI 가 계획을 바꿨다고 했는데 서버가 적용하지 못한 경우(바꿀 태스크 목록 없음 등), 사용자가 바뀐 줄 알지 않도록 답변을 바로잡는다
            val reply = when {
                result.plan_changed && busyWindowOverlapsNothing -> noOverlapReply(busyWindow!!)
                result.plan_changed && updatedTasks == null -> PLAN_NOT_APPLIED_REPLY
                else -> result.reply_message
            }

            ChatTurnRow.new {
                this.dailyPlan = DailyPlanRow[dailyPlanId]
                role = "assistant"
                content = reply
                createdAt = OffsetDateTime.now()
            }

            ChatMessageResponse(
                reply = reply,
                planChanged = updatedTasks != null,
                updatedTasks = updatedTasks
            )
        }
    }

    /**
     * 그날 할일을 직접 고친다 (줄이기·늘리기·추가·삭제·특정 시각으로 옮기기, #306).
     * 시간이 실제로 비어 있는지는 [applyChatEdits] 가 판단하고, 답변은 AI 문장 대신 실제로 적용·거절한 결과로 만든다.
     */
    private suspend fun editTasks(dailyPlanId: Int, userMessage: String, context: ChatContext, edits: List<AiChatEdit>): ChatMessageResponse {
        val planDate = LocalDate.parse(context.planDate)
        // 할일을 둘 수 없는 시간: 수면·등교·불가능 시간(NICE 조회라 트랜잭션 밖) + 완료한 할일 + 같은 날 다른 보드의 할일
        val unavailable = PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = planDate,
            endDate = planDate,
            schoolId = context.schoolId,
            classNumber = context.classNumber,
            grade = context.grade,
            customRows = context.customUnavailableRows,
            sleepTime = context.sleepTime
        )[planDate].orEmpty()
        val day = EditDay(
            blocked = unavailable + context.completedRanges + context.otherBoardRanges,
            earliestMinute = earliestMinuteOn(planDate),
            subjectIdsByName = context.subjectIdsByName
        )
        val edited = applyChatEdits(context.editable, edits, day)
        val reply = (edited.applied + edited.rejected).joinToString(" ").ifBlank { PLAN_NOT_APPLIED_REPLY }
        if (!edited.changed) {
            return saveTurns(dailyPlanId, userMessage, reply, ChatMessageResponse(reply = reply, planChanged = false))
        }

        val before = context.editable.associateBy { it.id }
        return newSuspendedTransaction {
            val dailyPlan = DailyPlanRow[dailyPlanId]
            edited.deletedIds.forEach { PlanTaskRow.findById(it)?.delete() }
            edited.tasks.forEach { task ->
                if (task.id == null) {
                    PlanTaskRow.new {
                        this.dailyPlan = dailyPlan
                        taskName = task.name
                        startTime = minutesToTime(task.start)
                        endTime = minutesToTime(task.end)
                        estimatedMinutes = task.minutes
                        subjectId = task.subjectId?.let { EntityID(it, SubjectTable) }
                    }
                } else if (before[task.id] != task) {
                    PlanTaskRow.findById(task.id)?.apply {
                        startTime = minutesToTime(task.start)
                        endTime = minutesToTime(task.end)
                        estimatedMinutes = task.minutes // 퀴즈 문항 수·캘린더 합계 기준이라 바뀐 길이로 맞춘다
                    }
                }
            }

            val dayRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .toList()
            val subjectNames = subjectNamesOf(dayRows)
            val updatedTasks = dayRows.map { it.toResponse(dailyPlanId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames)) }
            saveTurnsInTransaction(dailyPlanId, userMessage, reply)
            ChatMessageResponse(reply = reply, planChanged = true, updatedTasks = updatedTasks)
        }
    }

    /** 오늘 계획이면 지금 이후(10분 단위 올림)만, 다른 날이면 하루 전체 */
    private fun earliestMinuteOn(planDate: LocalDate): Int =
        if (planDate == todayInAppZone(clock)) {
            val now = LocalTime.now(clock.withZone(APP_ZONE))
            minOf(((now.hour * 60 + now.minute + 9) / 10) * 10, 24 * 60)
        } else {
            0
        }

    /**
     * 할일을 다른 날로 미룬다. 옮길 날의 빈 시간(수면·등교·불가능 시간·그날의 모든 기존 할일을 피해서)에 순서대로 넣고,
     * 원래 날짜를 postponed_from_date 에 남긴다. 바로 전날에서 미뤄져 온 할일은 이틀 연속 미루지 못하게 막는다.
     * 답변은 AI 문장 대신 실제로 옮긴 결과로 서버가 만든다 (옮기지 못했는데 옮겼다고 말하지 않도록).
     */
    private suspend fun postponeTasks(
        userId: Int,
        dailyPlanId: Int,
        userMessage: String,
        context: ChatContext,
        postpone: AiChatPostpone,
        aiReply: String
    ): ChatMessageResponse {
        val planDate = LocalDate.parse(context.planDate)
        val today = todayInAppZone(clock)
        val toDate = postpone.to_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: planDate.plusDays(1)

        val names = postpone.task_names.map { it.trim() }.toSet()
        val requested = context.pendingExisting.filter { it.taskName.trim() in names }.sortedBy { it.startMinutes }
        val (requestedBlocked, movable) = requested.partition { postponedFromPreviousDay(context.pendingPostponedFrom[it.id], planDate) }
        // AI 가 미룰 수 없는 할일을 알아서 빼고 답변에서만 언급한 경우("전부 미뤄줘")도 이유를 알려준다
        val blocked = requestedBlocked + context.pendingExisting.filter {
            it !in requested && postponedFromPreviousDay(context.pendingPostponedFrom[it.id], planDate) && aiReply.contains(it.taskName)
        }

        val rejection = when {
            requested.isEmpty() -> POSTPONE_NOTHING_REPLY
            movable.isEmpty() -> postponeBlockedReply(blocked.map { it.taskName })
            !toDate.isAfter(planDate) || toDate.isBefore(today) -> "할일은 ${formatDay(planDate.plusDays(1).coerceAtLeast(today))} 이후 날짜로만 미룰 수 있어요."
            toDate.isAfter(context.planBoardEndDate) -> "플랜보드 기간이 ${formatDay(context.planBoardEndDate)}까지라서 ${formatDay(toDate)}로는 미룰 수 없어요."
            else -> null
        }
        if (rejection != null) return saveTurns(dailyPlanId, userMessage, rejection, ChatMessageResponse(reply = rejection, planChanged = false))

        // 옮길 날의 막힌 시간: 수면·등교·불가능 시간 + 그날의 모든 기존 할일(다른 보드 포함). NICE 조회는 트랜잭션 밖에서
        val existingOnTarget = newSuspendedTransaction { userTaskRanges(userId, toDate, toDate) }
        val busy = PlanTaskScheduler.withExistingTasks(
            PlanTaskScheduler.buildUnavailableRanges(
                schoolDataFetcher = schoolDataFetcher,
                startDate = toDate,
                endDate = toDate,
                schoolId = context.schoolId,
                classNumber = context.classNumber,
                grade = context.grade,
                customRows = context.customUnavailableRows,
                sleepTime = context.sleepTime
            ),
            existingOnTarget
        )[toDate].orEmpty()
        val placed = PlanTaskScheduler.placeTasks(
            movable.map { it.taskName to (it.endMinutes - it.startMinutes) },
            PlanTaskScheduler.freeIntervalsFromBusyRanges(busy)
        )
        if (placed.isEmpty()) {
            val reply = "${formatDay(toDate)}에는 빈 시간이 없어서 미루지 못했어요. 다른 날을 말씀해 주세요."
            return saveTurns(dailyPlanId, userMessage, reply, ChatMessageResponse(reply = reply, planChanged = false))
        }
        val moved = movable.take(placed.size) // placeTasks 는 앞에서부터 들어가는 만큼만 배치한다
        val leftBehind = movable.drop(placed.size).map { it.taskName }

        return newSuspendedTransaction {
            // 동시 요청에도 daily_plan 이 중복 생성되지 않도록 insertIgnore (DDL 유니크 제약과 짝)
            DailyPlanTable.insertIgnore {
                it[DailyPlanTable.planBoardId] = context.planBoardId
                it[DailyPlanTable.planDate] = toDate
            }
            val targetPlan = DailyPlanRow.find {
                (DailyPlanTable.planBoardId eq context.planBoardId) and (DailyPlanTable.planDate eq toDate)
            }.first()

            val movedRows = moved.zip(placed).map { (task, slot) ->
                PlanTaskRow[task.id].apply {
                    dailyPlan = targetPlan
                    startTime = slot.startTime
                    endTime = slot.endTime
                    postponedFromDate = planDate
                }
            }
            val remainingRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .toList()
            val subjectNames = subjectNamesOf(movedRows + remainingRows)
            val movedResponses = movedRows.map {
                it.toResponse(targetPlan.id.value, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames))
            }
            val remaining = remainingRows.map {
                it.toResponse(dailyPlanId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames))
            }

            val reply = buildString {
                append("${moved.joinToString(", ") { "'${it.taskName}'" }} 할일을 ${formatDay(toDate)}")
                append(" ${movedResponses.joinToString(", ") { "${it.startTime}~${it.endTime}" }}로 미뤘어요.")
                if (leftBehind.isNotEmpty()) append(" ${leftBehind.joinToString(", ") { "'$it'" }}은(는) 그날 빈 시간이 부족해서 그대로 뒀어요.")
                if (blocked.isNotEmpty()) append(" ${blocked.joinToString(", ") { "'${it.taskName}'" }}은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요.")
            }
            saveTurnsInTransaction(dailyPlanId, userMessage, reply)
            ChatMessageResponse(reply = reply, planChanged = true, updatedTasks = remaining, movedTasks = movedResponses)
        }
    }

    private suspend fun saveTurns(dailyPlanId: Int, userMessage: String, reply: String, response: ChatMessageResponse): ChatMessageResponse =
        newSuspendedTransaction {
            saveTurnsInTransaction(dailyPlanId, userMessage, reply)
            response
        }

    private fun saveTurnsInTransaction(dailyPlanId: Int, userMessage: String, reply: String) {
        ChatTurnRow.new {
            this.dailyPlan = DailyPlanRow[dailyPlanId]
            role = "user"
            content = userMessage
            createdAt = OffsetDateTime.now()
        }
        ChatTurnRow.new {
            this.dailyPlan = DailyPlanRow[dailyPlanId]
            role = "assistant"
            content = reply
            createdAt = OffsetDateTime.now()
        }
    }

    suspend fun getHistory(userId: Int, dailyPlanId: Int): List<ChatTurnResponse> = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        ChatTurnRow.find { ChatTurnTable.dailyPlanId eq dailyPlanId }
            .orderBy(ChatTurnTable.createdAt to SortOrder.ASC)
            .map {
                ChatTurnResponse(
                    role = it.role,
                    content = it.content,
                    createdAt = it.createdAt.toString()
                )
            }
    }
}

/** AI 가 준 공부 불가능 시간대("HH:mm") 를 파싱한다. 형식이 틀리거나 시작이 끝보다 늦으면 null */
private fun busyWindowOf(start: String?, end: String?): Pair<LocalTime, LocalTime>? {
    val from = start?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
    val to = end?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
    return if (from.isBefore(to)) from to to else null
}

private fun overlaps(task: AiChatCurrentTask, window: Pair<LocalTime, LocalTime>): Boolean =
    LocalTime.parse(task.start_time).isBefore(window.second) && window.first.isBefore(LocalTime.parse(task.end_time))

internal const val POSTPONE_NOTHING_REPLY = "미룰 할일을 찾지 못했어요. 미루고 싶은 할일 이름을 말씀해 주세요."

internal fun postponeBlockedReply(taskNames: List<String>): String =
    "${taskNames.joinToString(", ") { "'$it'" }}은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요. 오늘 꼭 해봐요!"

/** 바로 전날에서 미뤄져 온 할일인지 — 그렇다면 이틀 연속 미루기라서 막는다 */
internal fun postponedFromPreviousDay(postponedFromDate: LocalDate?, planDate: LocalDate): Boolean =
    postponedFromDate == planDate.minusDays(1)

private fun formatDay(date: LocalDate): String =
    "${date.monthValue}월 ${date.dayOfMonth}일(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

private fun PlanTaskRow.toResponse(dailyPlanId: Int, planBoardId: Int, isManual: Boolean, subjectName: String?) = PlanTaskResponse(
    id = id.value,
    dailyPlanId = dailyPlanId,
    planBoardId = planBoardId,
    isManual = isManual,
    taskName = taskName,
    startTime = startTime.toString(),
    endTime = endTime.toString(),
    estimatedMinutes = estimatedMinutes,
    isCompleted = isCompleted,
    subjectId = subjectId?.value,
    subjectName = subjectName
)

internal fun noOverlapReply(window: Pair<LocalTime, LocalTime>): String =
    "그 시간(${window.first}~${window.second})에는 잡혀 있는 공부가 없어서 오늘 계획은 그대로 둘게요."

/**
 * 챗봇 프롬프트의 <PLAN_BOARD> 에 넣는 플랜보드 요약. 학생이 시험 날짜·남은 기간을 물으면 AI 가 이 값으로 답한다
 * (이게 없으면 AI 가 TARGET_DATE 를 시험일처럼 답했음). D-day 는 대화 중인 날짜(planDate) 기준.
 */
internal fun planBoardSummaryOf(title: String, startDate: LocalDate, endDate: LocalDate, examDate: LocalDate?, planDate: LocalDate): String {
    val examLine = examDate?.let {
        val days = ChronoUnit.DAYS.between(planDate, it)
        val dDay = when {
            days > 0 -> "D-$days"
            days == 0L -> "D-day(오늘)"
            else -> "이미 지남(${-days}일 전)"
        }
        "시험일: $it ($dDay)"
    } ?: "시험일: 등록되지 않음"
    return "플랜보드: $title\n학습 기간: $startDate ~ $endDate\n$examLine"
}

private data class ChatContext(
    val planDate: String,
    val dayOfWeekLabel: String,
    val pendingTasks: List<AiChatCurrentTask>,
    val pendingExisting: List<ExistingPendingTask>,
    val completedRanges: List<Pair<Int, Int>>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>,
    val sleepTime: SleepTime,
    val studyStyleSummary: String,
    val planBoardSummary: String,
    val history: List<AiChatTurn>,
    val planBoardId: Int,
    val planBoardIsManual: Boolean,
    val planBoardEndDate: LocalDate,
    val pendingPostponedFrom: Map<Int, LocalDate?>, // 미완료 태스크 id → 미뤄져 온 원래 날짜
    val editable: List<EditableTask>, // 할일 직접 편집 대상 (미완료 할일 + 과목·퀴즈 상태)
    val otherBoardRanges: List<Pair<Int, Int>>, // 같은 날 다른 플랜보드의 할일 시간 (편집할 때 겹치지 않게)
    val planSubjectsText: String, // 프롬프트 <PLAN_SUBJECTS>
    val subjectIdsByName: Map<String, Int> // 플랜보드 과목명 → 과목 id (추가하는 할일에 과목을 붙일 때)
)

private fun minutesToTime(minutes: Int): LocalTime =
    if (minutes >= 24 * 60) LocalTime.of(23, 59) else LocalTime.of(minutes / 60, minutes % 60)

/**
 * 할일별 태스크 퀴즈 상태. 키가 없으면 퀴즈를 시작하지 않은 할일, false 면 진행 중, true 면 결과 확정
 * (통과 / 마지막 차수까지 불합격 — PlanTaskService 의 확정 판정과 같은 기준). 트랜잭션 안에서 부른다.
 */
private fun quizStateOf(taskIds: List<Int>): Map<Int, Boolean> {
    if (taskIds.isEmpty()) return emptyMap()
    return TaskQuizAttemptTable.selectAll()
        .where { TaskQuizAttemptTable.planTaskId inList taskIds }
        .groupBy({ it[TaskQuizAttemptTable.planTaskId].value }, {
            it[TaskQuizAttemptTable.passed] == true ||
                (it[TaskQuizAttemptTable.passed] == false && it[TaskQuizAttemptTable.attemptNumber] >= MAX_ATTEMPTS)
        })
        .mapValues { (_, finalized) -> finalized.any { it } }
}

private class PlanSubjectLine(val subjectId: Int, val subjectName: String, val line: String)

/** 프롬프트 <PLAN_SUBJECTS> 용 플랜보드 과목·학습 범위 ("- 과학 (교과서): 단원, 단원, ..."). 트랜잭션 안에서 부른다 */
private fun planSubjectsOf(planBoardId: Int): List<PlanSubjectLine> =
    PlanSubjectRow.find { PlanSubjectTable.planBoardId eq planBoardId }.mapNotNull { row ->
        val textbook = row.textbook
        val subject = SubjectRow.findById(textbook.subject.id.value) ?: return@mapNotNull null
        val topics = orderedChaptersInRange(textbook, row.startChapter, row.endChapter).map { it.chapterName } +
            listOfNotNull(row.customRangeText?.takeIf { it.isNotBlank() })
        PlanSubjectLine(
            subjectId = subject.id.value,
            subjectName = subject.name.trim(),
            line = "- ${subject.name} (${textbook.title}): ${topics.take(MAX_PROMPT_TOPICS).joinToString(", ")}"
        )
    }

private const val MAX_PROMPT_TOPICS = 40
