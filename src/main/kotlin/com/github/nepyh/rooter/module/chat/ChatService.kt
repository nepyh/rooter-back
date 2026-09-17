package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.dto.AiChatTurn
import com.github.nepyh.rooter.module.chat.dto.ChatMessageResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
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
    private val chatLlmClient: ChatLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher
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

            val profileRow = StudentProfileTable.selectAll()
                .where { StudentProfileTable.user eq userId }
                .firstOrNull()
            val grade = profileRow?.get(StudentProfileTable.grade) ?: 2
            val schoolId = profileRow?.get(StudentProfileTable.schoolId)
            val classNumber = profileRow?.get(StudentProfileTable.classNumber)
            val customUnavailableRows = UnavailableTimeTable.selectAll()
                .where { UnavailableTimeTable.user eq userId }
                .map {
                    it[UnavailableTimeTable.dayOfWeek].code.toInt() to
                        (PlanTaskScheduler.toMinutes(it[UnavailableTimeTable.startTime]) to PlanTaskScheduler.toMinutes(it[UnavailableTimeTable.endTime]))
                }

            val studyStyleSummary = StudyStyleAnswerTable.selectAll()
                .where { StudyStyleAnswerTable.userId eq userId }
                .orderBy(StudyStyleAnswerTable.questionNumber to SortOrder.ASC)
                .map { "문항${it[StudyStyleAnswerTable.questionNumber]}=${it[StudyStyleAnswerTable.answerOption]}" }
                .joinToString(", ")
                .ifBlank { "응답 없음" }

            val history = ChatTurnTable.selectAll()
                .where { ChatTurnTable.dailyPlanId eq dailyPlanId }
                .orderBy(ChatTurnTable.createdAt to SortOrder.ASC)
                .map { AiChatTurn(role = it[ChatTurnTable.role], content = it[ChatTurnTable.content]) }
                .takeLast(HISTORY_LIMIT)

            ChatContext(
                planDate = planDate.toString(),
                dayOfWeekLabel = planDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN),
                tasks = tasks,
                grade = grade,
                schoolId = schoolId,
                classNumber = classNumber,
                customUnavailableRows = customUnavailableRows,
                studyStyleSummary = studyStyleSummary,
                history = history
            )
        }

        val result = chatLlmClient.adjustPlan(
            grade = context.grade,
            studyStyleSummary = context.studyStyleSummary,
            targetDate = "${context.planDate} (${context.dayOfWeekLabel})",
            currentTasksJson = json.encodeToString(context.tasks.map { AiChatTask(it.first, it.second) }),
            chatHistoryJson = json.encodeToString(context.history),
            userMessage = trimmed
        )

        val update = result?.plan_update
        val shouldReplan = result != null && result.plan_changed && update != null && update.tasks.isNotEmpty()

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
            PlanTaskScheduler.freeIntervalsFromBusyRanges(busyRanges + extraBusy)
        } else {
            emptyList()
        }

        return newSuspendedTransaction {
            ChatTurnTable.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[role] = "user"
                it[content] = trimmed
                it[createdAt] = OffsetDateTime.now()
            }

            if (result == null) {
                val fallback = "죄송해요, 지금은 답변을 드릴 수 없어요. 잠시 후 다시 시도해주세요."
                ChatTurnTable.insert {
                    it[this.dailyPlanId] = dailyPlanId
                    it[role] = "assistant"
                    it[content] = fallback
                    it[createdAt] = OffsetDateTime.now()
                }
                return@newSuspendedTransaction ChatMessageResponse(reply = fallback, planChanged = false)
            }

            var updatedTasks: List<PlanTaskResponse>? = null
            if (shouldReplan) {
                val placed = PlanTaskScheduler.placeTasks(
                    update!!.tasks.map { it.task_name to it.estimated_minutes },
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

            ChatTurnTable.insert {
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

        ChatTurnTable.selectAll()
            .where { ChatTurnTable.dailyPlanId eq dailyPlanId }
            .orderBy(ChatTurnTable.createdAt to SortOrder.ASC)
            .map {
                ChatTurnResponse(
                    role = it[ChatTurnTable.role],
                    content = it[ChatTurnTable.content],
                    createdAt = it[ChatTurnTable.createdAt].toString()
                )
            }
    }
}

private data class ChatContext(
    val planDate: String,
    val dayOfWeekLabel: String,
    val tasks: List<Pair<String, Int>>,
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>,
    val studyStyleSummary: String,
    val history: List<AiChatTurn>
)
