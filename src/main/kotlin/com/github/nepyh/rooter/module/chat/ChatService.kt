package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.AiChatCurrentTask
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
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
    private val schoolDataFetcher: SchoolDataFetcher
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
                    AiChatCurrentTask(it.taskName, it.estimatedMinutes, it.startTime.toString(), it.endTime.toString())
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
                history = history
            )
        }

        val result = chatLlmClient.adjustPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            targetDate = "${context.planDate} (${context.dayOfWeekLabel})",
            currentTasksJson = json.encodeToString(context.pendingTasks),
            chatHistoryJson = json.encodeToString(context.history),
            planBoardSummary = context.planBoardSummary,
            userMessage = trimmed
        )

        val update = result?.plan_update
        // AI 는 시각 비교를 자주 틀린다 (18:00~18:30 태스크가 19~21시와 겹친다고 판단하는 식).
        // 새로 생긴 공부 불가능 시간이 남은 태스크와 겹치는지는 서버가 직접 판단하고, 안 겹치면 계획을 건드리지 않는다.
        val busyWindow = update?.let { busyWindowOf(it.busy_window_start, it.busy_window_end) }
        val busyWindowOverlapsNothing = busyWindow != null && context.pendingTasks.none { overlaps(it, busyWindow) }
        val shouldReplan = result != null && result.plan_changed && update != null && update.tasks.isNotEmpty() &&
            !busyWindowOverlapsNothing

        // NICE 시간표 조회(네트워크 호출)가 있어 트랜잭션 밖에서 미리 계산해둔다.
        val freeIntervals = if (shouldReplan) {
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
            PlanTaskScheduler.freeIntervalsFromBusyRanges(busyRanges + extraBusy + context.completedRanges)
        } else {
            emptyList()
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
                val placed = PlanTaskScheduler.placeTasks(
                    update!!.tasks.map { it.task_name to it.estimated_minutes },
                    freeIntervals
                )

                val dailyPlan = DailyPlanRow[dailyPlanId]
                PlanTaskRow.find { (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.isCompleted eq false) }
                    .forEach { it.delete() }
                val keptTasks = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.map {
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
                val newTasks = placed.map { task ->
                    val planTask = PlanTaskRow.new {
                        this.dailyPlan = dailyPlan
                        taskName = task.taskName
                        startTime = task.startTime
                        endTime = task.endTime
                        estimatedMinutes = task.estimatedMinutes
                    }
                    PlanTaskResponse(
                        id = planTask.id.value,
                        dailyPlanId = dailyPlanId,
                        taskName = task.taskName,
                        startTime = task.startTime.toString(),
                        endTime = task.endTime.toString(),
                        estimatedMinutes = task.estimatedMinutes,
                        isCompleted = false
                    )
                }
                updatedTasks = (keptTasks + newTasks).sortedBy { it.startTime }
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
    val completedRanges: List<Pair<Int, Int>>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>,
    val studyStyleSummary: String,
    val planBoardSummary: String,
    val history: List<AiChatTurn>
)
