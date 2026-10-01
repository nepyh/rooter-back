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
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.userTaskRanges
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerRow
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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

            val studyStyleSummary = StudyStyleAnswerRow.find { StudyStyleAnswerTable.userId eq userId }
                .orderBy(StudyStyleAnswerTable.questionNumber to SortOrder.ASC)
                .map { "문항${it.questionNumber}=${it.answerOption}" }
                .joinToString(", ")
                .ifBlank { "응답 없음" }

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
                        can_postpone = !postponedFromPreviousDay(it.postponedFromDate, planDate)
                    )
                },
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
                studyStyleSummary = studyStyleSummary,
                planBoardSummary = planBoardSummaryOf(board.title, board.startDate, board.endDate, board.examDate, planDate),
                history = history,
                planBoardId = board.id.value,
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
            userMessage = trimmed
        )

        // 다른 날로 미뤄 달라는 요청이면 그날 계획 재구성 대신 미루기만 한다
        result?.postpone?.takeIf { it.task_names.isNotEmpty() }?.let { postpone ->
            return postponeTasks(userId, dailyPlanId, trimmed, context, postpone, result.reply_message)
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
                customRows = context.customUnavailableRows
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

                updatedTasks = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                    .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                    .map {
                        PlanTaskResponse(
                            id = it.id.value,
                            dailyPlanId = dailyPlanId,
                            taskName = it.taskName,
                            startTime = it.startTime.toString(),
                            endTime = it.endTime.toString(),
                            estimatedMinutes = it.estimatedMinutes,
                            isCompleted = it.isCompleted
                        )
                    }
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
                customRows = context.customUnavailableRows
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

            val movedResponses = moved.zip(placed).map { (task, slot) ->
                val row = PlanTaskRow[task.id]
                row.dailyPlan = targetPlan
                row.startTime = slot.startTime
                row.endTime = slot.endTime
                row.postponedFromDate = planDate
                row.toResponse(targetPlan.id.value)
            }
            val remaining = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .map { it.toResponse(dailyPlanId) }

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

private fun PlanTaskRow.toResponse(dailyPlanId: Int) = PlanTaskResponse(
    id = id.value,
    dailyPlanId = dailyPlanId,
    taskName = taskName,
    startTime = startTime.toString(),
    endTime = endTime.toString(),
    estimatedMinutes = estimatedMinutes,
    isCompleted = isCompleted
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
    val studyStyleSummary: String,
    val planBoardSummary: String,
    val history: List<AiChatTurn>,
    val planBoardId: Int,
    val planBoardEndDate: LocalDate,
    val pendingPostponedFrom: Map<Int, LocalDate?> // 미완료 태스크 id → 미뤄져 온 원래 날짜
)
