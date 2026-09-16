package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.dto.AiChatTurn
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.chat.model.ChatTurns
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswers
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

/** 한 번의 챗봇 호출에 함께 실어 보내는 최근 대화 턴 수. 너무 길면 프롬프트가 불필요하게 커진다. */
private const val HISTORY_LIMIT = 10

class ChatService(
    private val chatLlmClient: ChatLlmClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun requireOwnedDailyPlan(userId: Int, dailyPlanId: Int) =
        (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw DailyPlanNotFoundException()

    suspend fun sendMessage(userId: Int, dailyPlanId: Int, message: String): ChatMessageResponse {
        val trimmed = message.trim()
        if (trimmed.isBlank()) throw ChatValidationException.MessageRequiredException()

        val context = newSuspendedTransaction {
            val dailyPlanRow = requireOwnedDailyPlan(userId, dailyPlanId)
            val planDate = dailyPlanRow[DailyPlanTable.planDate]

            val tasks = PlanTaskTable.selectAll()
                .where { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
                .map { it[PlanTaskTable.taskName] to it[PlanTaskTable.estimatedMinutes] }

            val grade = StudentProfileTable.selectAll()
                .where { StudentProfileTable.user eq userId }
                .firstOrNull()
                ?.get(StudentProfileTable.grade)
                ?: 2

            val studyStyleSummary = StudyStyleAnswers.selectAll()
                .where { StudyStyleAnswers.userId eq userId }
                .orderBy(StudyStyleAnswers.questionNumber to SortOrder.ASC)
                .map { "문항${it[StudyStyleAnswers.questionNumber]}=${it[StudyStyleAnswers.answerOption]}" }
                .joinToString(", ")
                .ifBlank { "응답 없음" }

            val history = ChatTurns.selectAll()
                .where { ChatTurns.dailyPlanId eq dailyPlanId }
                .orderBy(ChatTurns.createdAt to SortOrder.ASC)
                .map { AiChatTurn(role = it[ChatTurns.role], content = it[ChatTurns.content]) }
                .takeLast(HISTORY_LIMIT)

            ChatContext(planDate.toString(), planDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), tasks, grade, studyStyleSummary, history)
        }

        val result = chatLlmClient.adjustPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            targetDate = "${context.planDate} (${context.dayOfWeekLabel})",
            currentTasksJson = json.encodeToString(context.tasks.map { AiChatTask(it.first, it.second) }),
            chatHistoryJson = json.encodeToString(context.history),
            userMessage = trimmed
        )

        return newSuspendedTransaction {
            ChatTurns.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[role] = "user"
                it[content] = trimmed
                it[createdAt] = OffsetDateTime.now()
            }

            if (result == null) {
                val fallback = "죄송해요, 지금은 답변을 드릴 수 없어요. 잠시 후 다시 시도해주세요."
                ChatTurns.insert {
                    it[this.dailyPlanId] = dailyPlanId
                    it[role] = "assistant"
                    it[content] = fallback
                    it[createdAt] = OffsetDateTime.now()
                }
                return@newSuspendedTransaction ChatMessageResponse(reply = fallback, planChanged = false)
            }

            var updatedTasks: List<PlanTaskResponse>? = null
            val update = result.plan_update
            if (result.plan_changed && update != null && update.tasks.isNotEmpty()) {
                val unavailableRanges = PlanTaskScheduler.loadUnavailableRanges(userId)
                val extraBusy = if (update.busy_window_start != null && update.busy_window_end != null) {
                    listOf(
                        PlanTaskScheduler.toMinutes(LocalTime.parse(update.busy_window_start)) to
                            PlanTaskScheduler.toMinutes(LocalTime.parse(update.busy_window_end))
                    )
                } else {
                    emptyList()
                }
                val freeIntervals = PlanTaskScheduler.freeIntervalsForDate(
                    LocalDate.parse(context.planDate),
                    unavailableRanges,
                    extraBusy
                )
                val placed = PlanTaskScheduler.placeTasks(
                    update.tasks.map { it.task_name to it.estimated_minutes },
                    freeIntervals
                )

                PlanTaskTable.deleteWhere { PlanTaskTable.dailyPlanId eq dailyPlanId }
                updatedTasks = placed.map { task ->
                    val id = PlanTaskTable.insert {
                        it[this.dailyPlanId] = dailyPlanId
                        it[taskName] = task.taskName
                        it[startTime] = task.startTime
                        it[endTime] = task.endTime
                        it[estimatedMinutes] = task.estimatedMinutes
                    } get PlanTaskTable.id
                    PlanTaskResponse(
                        id = id.value,
                        taskName = task.taskName,
                        startTime = task.startTime.toString(),
                        endTime = task.endTime.toString(),
                        estimatedMinutes = task.estimatedMinutes,
                        isCompleted = false
                    )
                }
            }

            ChatTurns.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[role] = "assistant"
                it[content] = result.reply_message
                it[createdAt] = OffsetDateTime.now()
            }

            ChatMessageResponse(
                reply = result.reply_message,
                planChanged = updatedTasks != null,
                updatedTasks = updatedTasks
            )
        }
    }

    suspend fun getHistory(userId: Int, dailyPlanId: Int): List<ChatTurnResponse> = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        ChatTurns.selectAll()
            .where { ChatTurns.dailyPlanId eq dailyPlanId }
            .orderBy(ChatTurns.createdAt to SortOrder.ASC)
            .map {
                ChatTurnResponse(
                    role = it[ChatTurns.role],
                    content = it[ChatTurns.content],
                    createdAt = it[ChatTurns.createdAt].toString()
                )
            }
    }
}

private data class ChatContext(
    val planDate: String,
    val dayOfWeekLabel: String,
    val tasks: List<Pair<String, Int>>,
    val grade: Int,
    val studyStyleSummary: String,
    val history: List<AiChatTurn>
)
