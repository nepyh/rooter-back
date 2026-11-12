package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.chat.dto.AiChatCurrentTask
import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.dto.AiChatPostpone
import com.github.nepyh.rooter.module.chat.dto.AiChatTurn
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.dto.ChatUpdatedDay
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.chat.model.ChatTurnRow
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.UserRoutine
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskOrigin
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.userRoutine
import com.github.nepyh.rooter.module.planboard.userTaskRanges
import com.github.nepyh.rooter.module.planboard.isManualPlanBoard
import com.github.nepyh.rooter.module.planboard.subjectNameFrom
import com.github.nepyh.rooter.module.planboard.subjectNamesOf
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.userStudyStyle
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
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
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 한 번의 챗봇 호출에 함께 실어 보내는 최근 대화 턴 수. 너무 길면 프롬프트가 불필요하게 커진다. */
private const val HISTORY_LIMIT = 10

/** AI 호출이 실패했거나 응답을 못 읽었을 때의 답변 */
internal const val LLM_FAILED_REPLY = "죄송해요, 지금은 답변을 드릴 수 없어요. 잠시 후 다시 시도해주세요."

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

    /**
     * 챗봇에게 메시지를 보낸다. [sessionId] 세션에 이어 붙이고 AI 에는 그 세션 대화만 히스토리로 넣는다 (#342).
     * [sessionId] 가 없으면 이 날 계획으로 마지막에 대화한 세션, 그것도 없으면 새 세션에 저장한다.
     */
    suspend fun sendMessage(userId: Int, dailyPlanId: Int, message: String, sessionId: Int? = null): ChatMessageResponse {
        val trimmed = message.trim()
        if (trimmed.isBlank()) throw ChatValidationException.MessageRequiredException()

        val context = newSuspendedTransaction {
            val dailyPlanRow = requireOwnedDailyPlan(userId, dailyPlanId)
            val session = sessionToContinue(userId, dailyPlanId, sessionId)
            val planDate = dailyPlanRow.planDate
            val board = dailyPlanRow.planBoard

            // 이미 완료·실패한 태스크는 재조정 대상이 아니다 — AI 에는 대기 태스크만 보내고,
            // 완료·실패 태스크의 시간대는 재배치할 때 막아둔다.
            val (completedTasks, pendingTasks) = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .partition { it.status != PlanTaskStatus.PENDING }

            val profileRow = StudentProfileRow.find { StudentProfileTable.user eq userId }
                .firstOrNull()
            val grade = profileRow?.grade ?: 2
            val schoolId = profileRow?.schoolId
            val classNumber = profileRow?.classNumber

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

            // 플랜보드 기간 전체 (#337) — AI 가 다른 날 할일도 보고 고칠 수 있게
            val days = loadPlanDays(userId, board.id.value, board.startDate, board.endDate)
            val today = todayInAppZone(clock)
            val editablePendingIds = days.values
                .filter { it.date == planDate || isEditableDay(it.date, today, board.endDate) }
                .flatMap { day -> day.pendingTasks.map { it.id } }

            // 새 세션은 빈 대화로 시작한다 — 다른 세션 대화는 AI 에 넣지 않는다 (#342)
            val history = session?.let { turnsOf(it.id.value) }.orEmpty()
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
                        quizFinalized = state == true,
                        aiGenerated = it.origin == PlanTaskOrigin.AI
                    )
                },
                otherBoardRanges = otherBoardRanges,
                planSubjectsText = planSubjects.joinToString("\n") { it.line },
                subjectIdsByName = planSubjects.associate { it.subjectName to it.subjectId },
                pendingExisting = pendingTasks.map {
                    ExistingPendingTask(
                        it.id.value, it.taskName,
                        PlanTaskScheduler.toMinutes(it.startTime), PlanTaskScheduler.toMinutes(it.endTime),
                        aiGenerated = it.origin == PlanTaskOrigin.AI
                    )
                },
                completedRanges = completedTasks.map {
                    PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime)
                },
                grade = grade,
                schoolId = schoolId,
                classNumber = classNumber,
                routine = userRoutine(userId),
                studyStyleSummary = studyStyleSummary,
                planBoardSummary = planBoardSummaryOf(board.title, board.startDate, board.endDate, board.examDate, planDate),
                history = history,
                sessionId = session?.id?.value,
                needsSessionTitle = session?.title == null,
                planBoardId = board.id.value,
                planBoardIsManual = isManualPlanBoard(board.examDate),
                planBoardStartDate = board.startDate,
                planBoardEndDate = board.endDate,
                pendingPostponedFrom = pendingTasks.associate { it.id.value to it.postponedFromDate },
                days = days,
                quizState = quizStateOf(editablePendingIds),
                planDaysText = planDaysText(days.values.sortedBy { it.date }, planDate, today, board.endDate)
            )
        }

        val result = chatLlmClient.adjustPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            targetDate = "${context.planDate} (${context.dayOfWeekLabel})",
            today = todayText(todayInAppZone(clock)),
            currentTasksJson = json.encodeToString(context.pendingTasks),
            chatHistoryJson = json.encodeToString(context.history),
            planBoardSummary = context.planBoardSummary,
            userMessage = trimmed,
            planSubjects = context.planSubjectsText,
            planDays = context.planDaysText,
            needsSessionTitle = context.needsSessionTitle
        )
        val aiTitle = result?.session_title

        // 다른 날에 생긴 일정 (#334) — 그날 계획(같은 플랜보드)에서 겹치는 할일만 옮긴다.
        // AI 가 plan_changed=false 로 답해도 날짜·시각을 줬으면 반영한다 (plan_changed 를 자주 틀린다)
        val planDate = LocalDate.parse(context.planDate)
        val otherBusy = result?.other_busy_days?.takeIf { it.isNotEmpty() }?.let {
            otherBusyDaysOf(it, planDate, todayInAppZone(clock), context.planBoardStartDate, context.planBoardEndDate)
        }

        // 다른 날로 미뤄 달라는 요청이면 그날 계획 재구성 대신 미루기만 한다. 다른 날 일정도 같이 말했으면 이어서 반영한다
        result?.postpone?.takeIf { it.task_names.isNotEmpty() }?.let { postpone ->
            val response = postponeTasks(userId, dailyPlanId, context, postpone, result.reply_message)
            return withOtherDays(userId, dailyPlanId, trimmed, context, response, otherBusy, aiTitle)
        }

        // 할일을 직접 줄이기·추가·삭제·옮기기 해 달라는 요청이면 그 동작만 검증해 적용한다 (#306). 다른 날 일정도 같이 말했으면 이어서 반영한다
        result?.edits?.takeIf { it.isNotEmpty() }?.let { edits ->
            return withOtherDays(userId, dailyPlanId, trimmed, context, editTasks(dailyPlanId, context, edits), otherBusy, aiTitle)
        }

        val update = result?.plan_update
        // AI 는 시각 비교를 자주 틀린다 (18:00~18:30 태스크가 19~21시와 겹친다고 판단하는 식).
        // 새로 생긴 공부 불가능 시간이 남은 태스크와 겹치는지는 서버가 직접 판단하고, 안 겹치면 계획을 건드리지 않는다.
        val busyWindow = update?.let { busyWindowOf(it.busy_window_start, it.busy_window_end) }
        val busyWindowOverlapsNothing = busyWindow != null && context.pendingTasks.none { overlaps(it, busyWindow) }
        // 다른 날 일정만 말했는데 AI 가 대화한 날 할일을 그대로 다시 보낸 경우엔 그날을 다시 배치하지 않는다.
        // 할일 이름·분이 바뀌었으면 대화한 날도 고쳐 달라고 한 것이라("오늘 수학 줄이고 수요일엔 학원") 다시 배치한다
        val onlyOtherDays = otherBusy != null && busyWindow == null && update != null && sameTasks(update.tasks, context.pendingTasks)
        val shouldReplan = result != null && result.plan_changed && update != null && update.tasks.isNotEmpty() &&
            !busyWindowOverlapsNothing && !onlyOtherDays
        val otherDayResults = otherBusy?.let { rescheduleOtherDays(userId, context, it) }.orEmpty()

        // NICE 시간표 조회(네트워크 호출)가 있어 트랜잭션 밖에서 미리 계산해둔다.
        val replanBusyRanges = if (shouldReplan) {
            val busyRanges = PlanTaskScheduler.buildUnavailableRanges(
                schoolDataFetcher = schoolDataFetcher,
                startDate = planDate,
                endDate = planDate,
                schoolId = context.schoolId,
                classNumber = context.classNumber,
                grade = context.grade,
                routine = context.routine
            )[planDate].orEmpty()
            val extraBusy = listOfNotNull(busyWindow?.let { PlanTaskScheduler.toMinutes(it.first) to PlanTaskScheduler.toMinutes(it.second) })
            // AI 가 만든 할일은 재조정에서 지우지도 옮기지도 않으므로 그 시간대를 막아둔다 (#245)
            val aiTaskRanges = context.pendingExisting.filter { it.aiGenerated }.map { it.startMinutes to it.endMinutes }
            busyRanges + extraBusy + context.completedRanges + aiTaskRanges
        } else {
            emptyList()
        }
        // 오늘 계획이면 옮기는 태스크를 지난 시간에 넣지 않는다 (10분 단위로 올림)
        val earliestMinute = if (planDate == todayInAppZone(clock)) {
            val now = LocalTime.now(clock.withZone(APP_ZONE))
            ((now.hour * 60 + now.minute + 9) / 10) * 10
        } else {
            0
        }

        return newSuspendedTransaction {
            if (result == null) {
                val saved = saveTurns(userId, context.sessionId, dailyPlanId, trimmed, LLM_FAILED_REPLY, aiTitle = null)
                return@newSuspendedTransaction ChatMessageResponse(reply = LLM_FAILED_REPLY, planChanged = false).withTurns(saved)
            }

            var updatedTasks: List<PlanTaskResponse>? = null
            if (shouldReplan) {
                // 안 겹치는 태스크는 제자리(id·시각 유지), 겹치는 것만 옮긴다 — rescheduleKeepingUnaffected 참고
                val rescheduled = rescheduleKeepingUnaffected(
                    // AI 가 만든 할일은 지울 수 없어서 재조정 대상에서 뺀다 — 손대지 않고 제자리에 둔다 (#245)
                    existing = context.pendingExisting.filter { !it.aiGenerated },
                    aiTasks = update!!.tasks.map { it.task_name to it.estimated_minutes },
                    busyRanges = replanBusyRanges,
                    earliestMinute = earliestMinute
                )

                val dailyPlan = DailyPlanRow[dailyPlanId]
                val keptIds = rescheduled.mapNotNull { it.existingId }.toSet()
                // AI 가 뺐거나 자리가 없어 못 넣은 대기 태스크만 지운다 (완료·실패 태스크는 건드리지 않음).
                // AI 가 만든 할일은 지울 수 없다 (#245)
                PlanTaskRow.find {
                    (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.status eq PlanTaskStatus.PENDING) and
                        (PlanTaskTable.origin eq PlanTaskOrigin.USER)
                }
                    .filter { it.id.value !in keptIds }
                    .forEach { it.delete() }
                rescheduled.forEach { task ->
                    // 챗봇이 만든 할일은 사용자가 만든 것으로 본다 — 챗봇 권한 = 유저 권한 (#245)
                    val row = task.existingId?.let { PlanTaskRow.findById(it) } ?: PlanTaskRow.new {
                        this.dailyPlan = dailyPlan
                        origin = PlanTaskOrigin.USER
                    }
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

            val updatedDays = otherDayResults.mapNotNull { it.applyInTransaction(context) }.takeIf { it.isNotEmpty() }

            // AI 가 계획을 바꿨다고 했는데 서버가 적용하지 못한 경우(바꿀 태스크 목록 없음 등), 사용자가 바뀐 줄 알지 않도록 답변을 바로잡는다
            val dayReply = when {
                result.plan_changed && busyWindowOverlapsNothing -> noOverlapReply(busyWindow!!)
                // 다른 날 일정만 말한 경우 — AI 문장 대신 아래 실제 결과로만 답한다
                updatedTasks == null && otherBusy != null -> null
                result.plan_changed && updatedTasks == null -> PLAN_NOT_APPLIED_REPLY
                else -> result.reply_message
            }
            // 다른 날은 AI 문장 대신 실제로 옮긴 결과로 답한다
            val otherReply = otherBusy?.let {
                otherDaysReply(it, otherDayResults.associate { day -> day.date to day.reschedule }, context.planBoardStartDate, context.planBoardEndDate)
            }
            val reply = listOfNotNull(dayReply, otherReply).joinToString(" ").ifBlank { PLAN_NOT_APPLIED_REPLY }

            val saved = saveTurns(userId, context.sessionId, dailyPlanId, trimmed, reply, aiTitle)

            ChatMessageResponse(
                reply = reply,
                planChanged = updatedTasks != null || updatedDays != null,
                updatedTasks = updatedTasks,
                updatedDays = updatedDays
            ).withTurns(saved)
        }
    }

    /**
     * 할일을 직접 고친다 (줄이기·늘리기·추가·삭제·특정 시각으로 옮기기, #306).
     * 편집마다 date 가 있으면 그날을 고친다 — 대화한 날 말고도 오늘부터 플랜보드 끝까지 (#337). 날짜별로 따로 판단·저장한다.
     * 시간이 실제로 비어 있는지는 [applyChatEdits] 가 판단하고, 답변은 AI 문장 대신 실제로 적용·거절한 결과로 만든다.
     */
    private suspend fun editTasks(dailyPlanId: Int, context: ChatContext, edits: List<AiChatEdit>): ChatMessageResponse {
        val planDate = LocalDate.parse(context.planDate)
        val today = todayInAppZone(clock)
        // 날짜가 없거나 형식이 틀리면 대화한 날. 대화한 날은 지금처럼 늘 고칠 수 있고, 다른 날은 오늘~플랜보드 끝만
        val byDate = edits.groupBy { edit -> edit.date?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: planDate }
        val (dates, lockedDates) = byDate.keys.sorted().partition { date ->
            date == planDate || (!date.isBefore(context.planBoardStartDate) && isEditableDay(date, today, context.planBoardEndDate))
        }

        // 할일을 둘 수 없는 시간: 수면·등교·불가능 시간(NICE 조회라 트랜잭션 밖) + 완료한 할일 + 같은 날 다른 보드의 할일
        val unavailable = if (dates.isEmpty()) emptyMap() else PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = dates.first(),
            endDate = dates.last(),
            schoolId = context.schoolId,
            classNumber = context.classNumber,
            grade = context.grade,
            routine = context.routine
        )
        val results = dates.map { date ->
            val day = context.days[date] ?: PlanDay(date, if (date == planDate) dailyPlanId else null, emptyList(), emptyList())
            val label = if (date == planDate) "오늘" else formatDay(date)
            val before = day.editableTasks(context.quizState)
            val edited = applyChatEdits(
                before,
                byDate.getValue(date),
                EditDay(
                    blocked = unavailable[date].orEmpty() + day.doneRanges + day.otherBoardRanges,
                    earliestMinute = earliestMinuteOn(date),
                    subjectIdsByName = context.subjectIdsByName,
                    dayLabel = label
                )
            )
            // 다른 날 결과 문장에는 어느 날인지 붙인다
            val messages = (edited.applied + edited.rejected).map { if (date == planDate || it.contains(label)) it else "$label $it" }
            DayEdit(date, day.dailyPlanId, before, edited, messages)
        }
        val reply = (results.flatMap { it.messages } + lockedDates.map { "${formatDay(it)}은(는) 지난 날이거나 플랜보드 기간 밖이라 고칠 수 없어요." })
            .joinToString(" ").ifBlank { PLAN_NOT_APPLIED_REPLY }
        val changed = results.filter { it.edited.changed }
        if (changed.isEmpty()) {
            return ChatMessageResponse(reply = reply, planChanged = false)
        }

        return newSuspendedTransaction {
            val savedDays = changed.map { dayEdit ->
                val planId = dayEdit.dailyPlanId ?: dailyPlanIdOn(context.planBoardId, dayEdit.date)
                val dailyPlan = DailyPlanRow[planId]
                val before = dayEdit.before.associateBy { it.id }
                dayEdit.edited.deletedIds.forEach { PlanTaskRow.findById(it)?.delete() }
                dayEdit.edited.tasks.forEach { task ->
                    if (task.id == null) {
                        PlanTaskRow.new {
                            this.dailyPlan = dailyPlan
                            taskName = task.name
                            startTime = minutesToTime(task.start)
                            endTime = minutesToTime(task.end)
                            estimatedMinutes = task.minutes
                            subjectId = task.subjectId?.let { EntityID(it, SubjectTable) }
                            // 사용자가 챗봇에게 시켜서 생긴 할일 = user 발 일정 (#245)
                            origin = PlanTaskOrigin.USER
                        }
                    } else if (before[task.id] != task) {
                        PlanTaskRow.findById(task.id)?.apply {
                            startTime = minutesToTime(task.start)
                            endTime = minutesToTime(task.end)
                            estimatedMinutes = task.minutes // 퀴즈 문항 수·캘린더 합계 기준이라 바뀐 길이로 맞춘다
                        }
                    }
                }
                ChatUpdatedDay(dayEdit.date.toString(), planId, dayTaskResponses(planId, context))
            }
            ChatMessageResponse(
                reply = reply,
                planChanged = true,
                updatedTasks = savedDays.firstOrNull { it.dailyPlanId == dailyPlanId }?.tasks,
                updatedDays = savedDays.filter { it.dailyPlanId != dailyPlanId }.takeIf { it.isNotEmpty() }
            )
        }
    }

    /** 그날 할일 전체를 시각 순으로 (응답용). 트랜잭션 안에서 부른다 */
    private fun dayTaskResponses(planId: Int, context: ChatContext): List<PlanTaskResponse> {
        val rows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq planId }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
            .toList()
        val subjectNames = subjectNamesOf(rows)
        return rows.map { it.toResponse(planId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames)) }
    }

    /** 플랜보드의 그날 계획 id. 없으면 만든다 — 동시 요청에도 중복 생성되지 않도록 insertIgnore (DDL 유니크 제약과 짝). 트랜잭션 안에서 부른다 */
    private fun dailyPlanIdOn(planBoardId: Int, date: LocalDate): Int {
        DailyPlanTable.insertIgnore {
            it[DailyPlanTable.planBoardId] = planBoardId
            it[DailyPlanTable.planDate] = date
        }
        return DailyPlanRow.find { (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate eq date) }.first().id.value
    }

    /**
     * 다른 날 일정([busy])에 맞춰 날마다 겹치는 할일을 어디로 옮길지 계산한다 (#334, 저장은 [OtherDayPlan.applyInTransaction]).
     * 같은 플랜보드의 그날 계획만 고치고, 그날 다른 플랜보드의 할일·완료한 할일·수면·등교·불가능 시간은 피한다.
     * 결과의 reschedule 이 null 이면 그날 계획이 없거나 겹치는 할일이 없는 날이다.
     */
    private suspend fun rescheduleOtherDays(userId: Int, context: ChatContext, busy: OtherBusyDays): List<OtherDayPlan> {
        if (busy.days.isEmpty()) return emptyList()
        val dates = busy.days.map { it.date }
        val rows = newSuspendedTransaction {
            (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (PlanBoardTable.userId eq userId) and (DailyPlanTable.planDate inList dates) }
                .map { row ->
                    OtherDayTaskRow(
                        date = row[DailyPlanTable.planDate],
                        dailyPlanId = row[DailyPlanTable.id].value,
                        sameBoard = row[DailyPlanTable.planBoardId].value == context.planBoardId,
                        task = ExistingPendingTask(
                            row[PlanTaskTable.id].value, row[PlanTaskTable.taskName],
                            PlanTaskScheduler.toMinutes(row[PlanTaskTable.startTime]), PlanTaskScheduler.toMinutes(row[PlanTaskTable.endTime]),
                            aiGenerated = row[PlanTaskTable.origin] == PlanTaskOrigin.AI
                        ),
                        pending = row[PlanTaskTable.status] == PlanTaskStatus.PENDING
                    )
                }
        }.groupBy { it.date }
        // 옮길 할일이 있는 날만 NICE 를 조회한다 (트랜잭션 밖)
        val withTasks = busy.days.filter { day -> rows[day.date].orEmpty().any { it.sameBoard && it.pending } }
        val unavailable = if (withTasks.isEmpty()) emptyMap() else PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = withTasks.first().date,
            endDate = withTasks.last().date,
            schoolId = context.schoolId,
            classNumber = context.classNumber,
            grade = context.grade,
            routine = context.routine
        )
        return busy.days.map { day ->
            val dayRows = rows[day.date].orEmpty()
            val (mine, others) = dayRows.partition { it.sameBoard && it.pending }
            OtherDayPlan(
                date = day.date,
                dailyPlanId = mine.firstOrNull()?.dailyPlanId,
                reschedule = if (mine.isEmpty()) null else rescheduleOtherDay(
                    pending = mine.map { it.task },
                    windows = day.windows,
                    blocked = unavailable[day.date].orEmpty() + others.map { it.task.startMinutes to it.task.endMinutes },
                    earliestMinute = earliestMinuteOn(day.date)
                )
            )
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
        context: ChatContext,
        postpone: AiChatPostpone,
        aiReply: String
    ): ChatMessageResponse {
        val today = todayInAppZone(clock)
        // 미룰 할일이 있는 날. 다른 날 할일도 미룰 수 있다 (#337) — 오늘~플랜보드 끝만
        val targetDate = LocalDate.parse(context.planDate)
        val planDate = postpone.from_date?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: targetDate
        val sourceDay = if (planDate == targetDate) null else context.days[planDate]
        if (planDate != targetDate && (sourceDay == null || !isEditableDay(planDate, today, context.planBoardEndDate))) {
            val reply = "${formatDay(planDate)}은(는) 지난 날이거나 플랜보드 기간 밖이라 할일을 미룰 수 없어요."
            return ChatMessageResponse(reply = reply, planChanged = false)
        }
        val sourcePlanId = sourceDay?.dailyPlanId ?: dailyPlanId
        val pendingExisting = sourceDay?.pendingTasks?.map { it.toExisting() } ?: context.pendingExisting
        val pendingPostponedFrom = sourceDay?.pendingTasks?.associate { it.id to it.postponedFromDate } ?: context.pendingPostponedFrom
        val toDate = postpone.to_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: planDate.plusDays(1)

        val names = postpone.task_names.map { it.trim() }.toSet()
        val requested = pendingExisting.filter { it.taskName.trim() in names }.sortedBy { it.startMinutes }
        val (requestedBlocked, movable) = requested.partition { postponedFromPreviousDay(pendingPostponedFrom[it.id], planDate) }
        // AI 가 미룰 수 없는 할일을 알아서 빼고 답변에서만 언급한 경우("전부 미뤄줘")도 이유를 알려준다
        val blocked = requestedBlocked + pendingExisting.filter {
            it !in requested && postponedFromPreviousDay(pendingPostponedFrom[it.id], planDate) && aiReply.contains(it.taskName)
        }

        val rejection = when {
            requested.isEmpty() -> POSTPONE_NOTHING_REPLY
            movable.isEmpty() -> postponeBlockedReply(blocked.map { it.taskName })
            !toDate.isAfter(planDate) || toDate.isBefore(today) -> "할일은 ${formatDay(planDate.plusDays(1).coerceAtLeast(today))} 이후 날짜로만 미룰 수 있어요."
            toDate.isAfter(context.planBoardEndDate) -> "플랜보드 기간이 ${formatDay(context.planBoardEndDate)}까지라서 ${formatDay(toDate)}로는 미룰 수 없어요."
            else -> null
        }
        if (rejection != null) return ChatMessageResponse(reply = rejection, planChanged = false)

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
                routine = context.routine
            ),
            existingOnTarget
        )[toDate].orEmpty()
        val placed = PlanTaskScheduler.placeTasks(
            movable.map { it.taskName to (it.endMinutes - it.startMinutes) },
            PlanTaskScheduler.freeIntervalsFromBusyRanges(busy)
        )
        if (placed.isEmpty()) {
            val reply = "${formatDay(toDate)}에는 빈 시간이 없어서 미루지 못했어요. 다른 날을 말씀해 주세요."
            return ChatMessageResponse(reply = reply, planChanged = false)
        }
        val moved = movable.take(placed.size) // placeTasks 는 앞에서부터 들어가는 만큼만 배치한다
        val leftBehind = movable.drop(placed.size).map { it.taskName }

        return newSuspendedTransaction {
            val targetPlan = DailyPlanRow[dailyPlanIdOn(context.planBoardId, toDate)]

            val movedRows = moved.zip(placed).map { (task, slot) ->
                PlanTaskRow[task.id].apply {
                    dailyPlan = targetPlan
                    startTime = slot.startTime
                    endTime = slot.endTime
                    postponedFromDate = planDate
                }
            }
            val remainingRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq sourcePlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .toList()
            val subjectNames = subjectNamesOf(movedRows + remainingRows)
            val movedResponses = movedRows.map {
                it.toResponse(targetPlan.id.value, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames))
            }
            val remaining = remainingRows.map {
                it.toResponse(sourcePlanId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames))
            }

            val reply = buildString {
                append("${moved.joinToString(", ") { "'${it.taskName}'" }} 할일을 ${formatDay(toDate)}")
                append(" ${movedResponses.joinToString(", ") { "${it.startTime}~${it.endTime}" }}로 미뤘어요.")
                if (leftBehind.isNotEmpty()) append(" ${leftBehind.joinToString(", ") { "'$it'" }}은(는) 그날 빈 시간이 부족해서 그대로 뒀어요.")
                if (blocked.isNotEmpty()) append(" ${blocked.joinToString(", ") { "'${it.taskName}'" }}은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요.")
            }
            // 대화한 날에서 미뤘으면 남은 할일은 updatedTasks, 다른 날에서 미뤘으면 그날을 updatedDays 로 (#337)
            if (sourcePlanId == dailyPlanId) {
                ChatMessageResponse(reply = reply, planChanged = true, updatedTasks = remaining, movedTasks = movedResponses)
            } else {
                ChatMessageResponse(
                    reply = reply, planChanged = true, movedTasks = movedResponses,
                    updatedDays = listOf(ChatUpdatedDay(planDate.toString(), sourcePlanId, remaining))
                )
            }
        }
    }

    /**
     * 할일 편집·미루기 결과([response])에 같은 메시지로 말한 다른 날 일정([otherBusy], #334)을 이어서 반영하고 대화를 저장한다.
     * 다른 날 계획은 편집·미루기가 저장된 뒤 DB 에서 다시 읽어 계산하므로, 같은 날을 함께 고쳐도 바뀐 계획 기준으로 옮긴다.
     */
    private suspend fun withOtherDays(
        userId: Int,
        dailyPlanId: Int,
        userMessage: String,
        context: ChatContext,
        response: ChatMessageResponse,
        otherBusy: OtherBusyDays?,
        aiTitle: String?
    ): ChatMessageResponse {
        val otherDayResults = otherBusy?.let { rescheduleOtherDays(userId, context, it) }.orEmpty()
        return newSuspendedTransaction {
            val otherDays = otherDayResults.mapNotNull { it.applyInTransaction(context) }
            val otherReply = otherBusy?.let {
                otherDaysReply(it, otherDayResults.associate { day -> day.date to day.reschedule }, context.planBoardStartDate, context.planBoardEndDate)
            }
            val reply = listOfNotNull(response.reply, otherReply).joinToString(" ")
            val saved = saveTurns(userId, context.sessionId, dailyPlanId, userMessage, reply, aiTitle)
            // 같은 날이 두 번 바뀌었으면 나중에 읽은(다른 날 일정까지 반영된) 그날 할일로
            val updatedDays = (response.updatedDays.orEmpty() + otherDays).associateBy { it.date }.values.toList()
            response.copy(
                reply = reply,
                planChanged = response.planChanged || otherDays.isNotEmpty(),
                updatedDays = updatedDays.takeIf { it.isNotEmpty() }
            ).withTurns(saved)
        }
    }

    /**
     * 보고 있는 계획 없이 챗봇에게 메시지를 보낸다 (#351). 교과서 질문·잡담에만 답하고 계획은 바꾸지 않는다 —
     * 계획을 바꿔 달라고 하면 AI 가 계획을 먼저 만들라고 안내한다. [sessionId] 세션에 이어 붙이고, 없으면 새 세션에 저장한다.
     * 저장되는 대화의 dailyPlanId 는 null 이다.
     */
    suspend fun sendMessageWithoutPlan(userId: Int, message: String, sessionId: Int? = null): ChatMessageResponse {
        val trimmed = message.trim()
        if (trimmed.isBlank()) throw ChatValidationException.MessageRequiredException()

        val context = newSuspendedTransaction {
            val session = sessionId?.let { requireOwnedSession(userId, it) }
            NoPlanContext(
                sessionId = session?.id?.value,
                needsSessionTitle = session?.title == null,
                grade = StudentProfileRow.find { StudentProfileTable.user eq userId }.firstOrNull()?.grade ?: 2,
                studyStyleSummary = userStudyStyle(userId).summary(),
                history = session?.let { turnsOf(it.id.value) }.orEmpty()
                    .map { AiChatTurn(role = it.role, content = it.content) }
                    .takeLast(HISTORY_LIMIT)
            )
        }

        val result = chatLlmClient.chatWithoutPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            today = todayText(todayInAppZone(clock)),
            chatHistoryJson = json.encodeToString(context.history),
            userMessage = trimmed,
            needsSessionTitle = context.needsSessionTitle
        )
        // AI 가 계획 필드를 채워 보내도 바꿀 계획이 없으니 답변만 쓴다
        val reply = result?.reply_message?.trim()?.takeIf { it.isNotBlank() } ?: LLM_FAILED_REPLY

        return newSuspendedTransaction {
            val saved = saveTurns(userId, context.sessionId, null, trimmed, reply, result?.session_title)
            ChatMessageResponse(reply = reply, planChanged = false).withTurns(saved)
        }
    }

    /** 이 날 계획을 보고 나눈 대화 전체 (세션 상관없이, 순서대로). 세션별 대화는 [ChatSessionService.getSessionTurns] */
    suspend fun getHistory(userId: Int, dailyPlanId: Int): List<ChatTurnResponse> = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        ChatTurnRow.find { ChatTurnTable.dailyPlanId eq dailyPlanId }
            .orderBy(ChatTurnTable.id to SortOrder.ASC)
            .map { it.toResponse() }
    }
}

/** 계획 없이 보내는 메시지(#351)에 AI 로 넘기는 맥락 */
private class NoPlanContext(
    val sessionId: Int?,
    val needsSessionTitle: Boolean,
    val grade: Int,
    val studyStyleSummary: String,
    val history: List<AiChatTurn>
)

private fun ChatMessageResponse.withTurns(saved: SavedTurns) = copy(
    sessionId = saved.sessionId,
    sessionTitle = saved.sessionTitle,
    userTurnId = saved.userTurnId,
    assistantTurnId = saved.assistantTurnId
)

/** AI 가 준 공부 불가능 시간대("HH:mm") 를 파싱한다. 형식이 틀리거나 시작이 끝보다 늦으면 null */
private fun busyWindowOf(start: String?, end: String?): Pair<LocalTime, LocalTime>? {
    val from = aiClockOf(start) ?: return null
    val to = aiClockOf(end) ?: return null
    return if (from.isBefore(to)) from to to else null
}

private fun overlaps(task: AiChatCurrentTask, window: Pair<LocalTime, LocalTime>): Boolean =
    LocalTime.parse(task.start_time).isBefore(window.second) && window.first.isBefore(LocalTime.parse(task.end_time))

/** AI 가 다시 보낸 할일 목록이 지금 할일과 이름·분까지 같은지 (순서는 무시) — 같으면 대화한 날은 고칠 게 없다 */
internal fun sameTasks(tasks: List<AiChatTask>, current: List<AiChatCurrentTask>): Boolean =
    tasks.map { it.task_name.trim() to it.estimated_minutes }.sortedBy { "${it.first}:${it.second}" } ==
        current.map { it.task_name.trim() to it.estimated_minutes }.sortedBy { "${it.first}:${it.second}" }

internal const val POSTPONE_NOTHING_REPLY = "미룰 할일을 찾지 못했어요. 미루고 싶은 할일 이름을 말씀해 주세요."

internal fun postponeBlockedReply(taskNames: List<String>): String =
    "${taskNames.joinToString(", ") { "'$it'" }}은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요. 오늘 꼭 해봐요!"

/** 바로 전날에서 미뤄져 온 할일인지 — 그렇다면 이틀 연속 미루기라서 막는다 */
internal fun postponedFromPreviousDay(postponedFromDate: LocalDate?, planDate: LocalDate): Boolean =
    postponedFromDate == planDate.minusDays(1)

private fun PlanTaskRow.toResponse(dailyPlanId: Int, planBoardId: Int, isManual: Boolean, subjectName: String?) = PlanTaskResponse(
    id = id.value,
    dailyPlanId = dailyPlanId,
    planBoardId = planBoardId,
    isManual = isManual,
    taskName = taskName,
    startTime = startTime.toString(),
    endTime = endTime.toString(),
    estimatedMinutes = estimatedMinutes,
    status = status,
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
    val routine: UserRoutine,
    val studyStyleSummary: String,
    val planBoardSummary: String,
    val history: List<AiChatTurn>,
    val sessionId: Int?, // 이어 붙일 세션. null 이면 저장할 때 새로 만든다 (#342)
    val needsSessionTitle: Boolean, // 세션 첫 메시지라 AI 에 제목을 받아야 하는지
    val planBoardId: Int,
    val planBoardIsManual: Boolean,
    val planBoardStartDate: LocalDate,
    val planBoardEndDate: LocalDate,
    val pendingPostponedFrom: Map<Int, LocalDate?>, // 미완료 태스크 id → 미뤄져 온 원래 날짜
    val editable: List<EditableTask>, // 할일 직접 편집 대상 (미완료 할일 + 과목·퀴즈 상태)
    val otherBoardRanges: List<Pair<Int, Int>>, // 같은 날 다른 플랜보드의 할일 시간 (편집할 때 겹치지 않게)
    val planSubjectsText: String, // 프롬프트 <PLAN_SUBJECTS>
    val subjectIdsByName: Map<String, Int>, // 플랜보드 과목명 → 과목 id (추가하는 할일에 과목을 붙일 때)
    val days: Map<LocalDate, PlanDay>, // 플랜보드 기간의 날짜별 할일 (#337)
    val quizState: Map<Int, Boolean>, // 고칠 수 있는 날 대기 할일의 퀴즈 상태 (quizStateOf)
    val planDaysText: String // 프롬프트 <PLAN_DAYS>
)

/** 하루치 편집 결과 (#337). [dailyPlanId] 가 null 이면 그날 계획이 아직 없어 저장할 때 만든다 */
private class DayEdit(
    val date: LocalDate,
    val dailyPlanId: Int?,
    val before: List<EditableTask>,
    val edited: EditResult,
    val messages: List<String>
)

/** 다른 날 일정 반영용으로 읽은 그날 할일 한 건 (같은 보드의 대기 할일이면 옮길 후보, 아니면 피할 시간) */
private data class OtherDayTaskRow(
    val date: LocalDate,
    val dailyPlanId: Int,
    val sameBoard: Boolean,
    val task: ExistingPendingTask,
    val pending: Boolean
)

/** 다른 날 하루의 재배치 계산 결과 (#334). [reschedule] 이 null 이면 계획이 없거나 겹치는 할일이 없어 그대로 */
private class OtherDayPlan(val date: LocalDate, val dailyPlanId: Int?, val reschedule: OtherDayReschedule?) {
    /** 옮긴 할일의 시각을 저장하고 그날 할일 전체를 돌려준다. 옮긴 게 없으면 null. 트랜잭션 안에서 부른다 */
    fun applyInTransaction(context: ChatContext): ChatUpdatedDay? {
        val moved = reschedule?.moved?.takeIf { it.isNotEmpty() } ?: return null
        val planId = dailyPlanId ?: return null
        moved.forEach { task ->
            PlanTaskRow.findById(task.existingId!!)?.apply {
                startTime = task.startTime
                endTime = task.endTime
            }
        }
        val dayRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq planId }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
            .toList()
        val subjectNames = subjectNamesOf(dayRows)
        return ChatUpdatedDay(
            date = date.toString(),
            dailyPlanId = planId,
            tasks = dayRows.map { it.toResponse(planId, context.planBoardId, context.planBoardIsManual, it.subjectNameFrom(subjectNames)) }
        )
    }
}

private fun minutesToTime(minutes: Int): LocalTime =
    if (minutes >= 24 * 60) LocalTime.of(23, 59) else LocalTime.of(minutes / 60, minutes % 60)

/**
 * 플랜보드 기간([start]~[end])의 날짜별 할일 (#337). 계획이 없는 날도 빈 날로 넣는다.
 * 같은 플랜보드 할일은 상태와 함께, 다른 플랜보드 할일은 피할 시간으로만 담는다. 트랜잭션 안에서 부른다.
 */
private fun loadPlanDays(userId: Int, planBoardId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, PlanDay> {
    val plans = DailyPlanRow.find {
        (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate greaterEq start) and (DailyPlanTable.planDate lessEq end)
    }.toList()
    val taskRows = if (plans.isEmpty()) emptyList() else PlanTaskRow.find { PlanTaskTable.dailyPlanId inList plans.map { it.id } }.toList()
    val subjectNames = subjectNamesOf(taskRows)
    val tasksByPlan = taskRows.groupBy { it.dailyPlan.id.value }
    val otherBoardRanges = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
        .selectAll()
        .where {
            (PlanBoardTable.userId eq userId) and (PlanBoardTable.id neq planBoardId) and
                (DailyPlanTable.planDate greaterEq start) and (DailyPlanTable.planDate lessEq end)
        }
        .groupBy({ it[DailyPlanTable.planDate] }, { PlanTaskScheduler.toMinutes(it[PlanTaskTable.startTime]) to PlanTaskScheduler.toMinutes(it[PlanTaskTable.endTime]) })
    val planByDate = plans.associateBy { it.planDate }

    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.associateWith { date ->
        val plan = planByDate[date]
        PlanDay(
            date = date,
            dailyPlanId = plan?.id?.value,
            tasks = plan?.let { tasksByPlan[it.id.value] }.orEmpty().map {
                PlanDayTask(
                    id = it.id.value,
                    name = it.taskName,
                    start = PlanTaskScheduler.toMinutes(it.startTime),
                    end = PlanTaskScheduler.toMinutes(it.endTime),
                    minutes = it.estimatedMinutes,
                    subjectId = it.subjectId?.value,
                    subjectName = it.subjectNameFrom(subjectNames),
                    status = it.status,
                    aiGenerated = it.origin == PlanTaskOrigin.AI,
                    postponedFromDate = it.postponedFromDate
                )
            },
            otherBoardRanges = otherBoardRanges[date].orEmpty()
        )
    }
}

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
