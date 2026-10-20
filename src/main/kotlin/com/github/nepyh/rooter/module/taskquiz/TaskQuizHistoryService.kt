package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.chapterNameToSubjectIds
import com.github.nepyh.rooter.module.planboard.guessTaskSubject
import com.github.nepyh.rooter.module.planboard.planBoardSubjects
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryChoiceResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryDetailResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryItemResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryListResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.exception.QuizHistoryNotFoundException
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 퀴즈 히스토리 목록의 한 페이지 크기 (복습 목록과 같게 50) */
const val QUIZ_HISTORY_PAGE_SIZE = 50

/**
 * 응시 상태. 퀴즈는 할일이 끝나면 한 일·안 한 일 가리지 않고 자동 생성되므로, 채점 전 차수를 기한으로 나눈다.
 * TODO = 채점 전이고 계획 날짜가 오늘(이후) · MISSED = 채점 전인데 계획 날짜가 지남(놓친 퀴즈, 지금 풀어도 제출되고 풀면 DONE) · DONE = 채점 끝
 * TODO: 할일 실패(plan_tasks.is_failed, PR #283) 가 들어오면 실패한 할일의 채점 전 차수도 MISSED 로 본다
 */
enum class QuizHistoryStatus { TODO, MISSED, DONE }

/**
 * 퀴즈 히스토리 조회. 조회 전용이라 LLM 호출도 상태 변경도 없다.
 * 지금은 태스크 완료 확인 퀴즈(차수 = 1항목)만 담고, 일일 퀴즈는 사양이 확정되면 같은 응답에 type=DAILY 로 합류한다.
 */
class TaskQuizHistoryService(private val clock: Clock = Clock.systemUTC()) {

    /** 본인 퀴즈를 최신순으로 limit 개. cursor 는 앞 페이지 응답의 nextCursor (id 커서라 조회 중 새 퀴즈가 생겨도 안 밀린다). */
    fun list(
        userId: Int,
        status: QuizHistoryStatus? = null,
        cursor: Int? = null,
        limit: Int = QUIZ_HISTORY_PAGE_SIZE
    ): QuizHistoryListResponse = transaction {
        val today = todayInAppZone(clock)

        // 소유자·상태·커서 조건을 한 번에 걸고 제목·날짜·시각까지 같이 읽으려면 조인이 필요해 Table DSL 을 쓴다
        fun condition(after: Int?): Op<Boolean> {
            var cond = PlanBoardTable.userId eq userId
            when (status) {
                QuizHistoryStatus.TODO -> cond = cond and TaskQuizAttemptTable.passed.isNull() and (DailyPlanTable.planDate greaterEq today)
                QuizHistoryStatus.MISSED -> cond = cond and TaskQuizAttemptTable.passed.isNull() and (DailyPlanTable.planDate less today)
                QuizHistoryStatus.DONE -> cond = cond and TaskQuizAttemptTable.passed.isNotNull()
                null -> Unit
            }
            if (after != null) cond = cond and (TaskQuizAttemptTable.id less after)
            return cond
        }

        val rows = (TaskQuizAttemptTable innerJoin PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { condition(cursor) }
            .orderBy(TaskQuizAttemptTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val page = rows.take(limit)

        val totalCount = (TaskQuizAttemptTable innerJoin PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { condition(null) }
            .count()
            .toInt()

        // 페이지에 걸린 보드들의 학습범위 교과서 (id 만 필요해 벌크 조회 — Row 로 읽으면 보드마다 lazy SELECT 가 붙는다)
        val boardIds = page.map { it[PlanBoardTable.id].value }.distinct()
        val textbookIdsByBoard: Map<Int, List<Int>> = if (boardIds.isEmpty()) {
            emptyMap()
        } else {
            PlanSubjectTable
                .select(PlanSubjectTable.planBoardId, PlanSubjectTable.textbookId)
                .where { PlanSubjectTable.planBoardId inList boardIds.map { EntityID(it, PlanBoardTable) } }
                .groupBy({ it[PlanSubjectTable.planBoardId].value }, { it[PlanSubjectTable.textbookId].value })
        }

        // 보드별로 단원 이름 -> 과목 후보, 그리고 폴백에 쓸 보드 과목 목록
        val chapterSubjectsByBoard = boardIds.associateWith { chapterNameToSubjectIds(textbookIdsByBoard[it].orEmpty()) }
        val boardSubjects = boardIds.associateWith { planBoardSubjects(it) }

        // 차수별 문항 단원 이름 (단원 미상은 뺀다)
        val chapterNamesByAttempt: Map<Int, List<String>> = if (page.isEmpty()) {
            emptyMap()
        } else {
            TaskQuizQuestionTable
                .select(TaskQuizQuestionTable.attemptId, TaskQuizQuestionTable.chapterName)
                .where {
                    (TaskQuizQuestionTable.attemptId inList page.map { it[TaskQuizAttemptTable.id] }) and
                        TaskQuizQuestionTable.chapterName.isNotNull()
                }
                .groupBy({ it[TaskQuizQuestionTable.attemptId].value }, { it[TaskQuizQuestionTable.chapterName]!! })
        }

        /** 페이지에 걸린 보드의 단원·과목 정보로 항목마다 과목을 정한다 */
        fun subjectOf(row: ResultRow): Pair<Int, String>? {
            val boardId = row[PlanBoardTable.id].value
            return pickSubject(
                chapterNames = chapterNamesByAttempt[row[TaskQuizAttemptTable.id].value].orEmpty(),
                chapterSubjects = chapterSubjectsByBoard[boardId].orEmpty(),
                taskName = row[PlanTaskTable.taskName],
                subjects = boardSubjects[boardId].orEmpty()
            )
        }

        QuizHistoryListResponse(
            quizzes = page.map { row ->
                val passed = row[TaskQuizAttemptTable.passed]
                val subject = subjectOf(row)

                QuizHistoryItemResponse(
                    quizId = row[TaskQuizAttemptTable.id].value,
                    type = "TASK",
                    status = statusOf(passed, row[DailyPlanTable.planDate], today).name,
                    planTaskId = row[TaskQuizAttemptTable.planTaskId].value,
                    attemptNumber = row[TaskQuizAttemptTable.attemptNumber],
                    title = row[PlanTaskTable.taskName],
                    subjectId = subject?.first,
                    subjectName = subject?.second,
                    createdAt = row[TaskQuizAttemptTable.createdAt].toString(),
                    scheduledAt = LocalDateTime.of(row[DailyPlanTable.planDate], row[PlanTaskTable.endTime]).toString(),
                    completedAt = row[TaskQuizAttemptTable.submittedAt]?.toString(),
                    correctCount = row[TaskQuizAttemptTable.correctCount],
                    totalCount = row[TaskQuizAttemptTable.totalCount],
                    passed = passed
                )
            },
            totalCount = totalCount,
            nextCursor = if (rows.size > limit) page.last()[TaskQuizAttemptTable.id].value else null
        )
    }

    /**
     * 차수 하나의 상세. 본인 차수만 조회된다.
     * 미응시(todo) 차수는 정답 여부·해설을 null 로 숨긴다 — 퀴즈 풀이 흐름이 답을 문항별로만 공개하는 규칙과 같게 둔다.
     */
    fun detail(userId: Int, quizId: Int): QuizHistoryDetailResponse = transaction {
        // 소유자 확인 때문에 플랜보드까지 조인해야 해서 Table DSL 을 쓴다
        val row = (TaskQuizAttemptTable innerJoin PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (TaskQuizAttemptTable.id eq quizId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizHistoryNotFoundException()

        val attemptId = row[TaskQuizAttemptTable.id].value
        val passed = row[TaskQuizAttemptTable.passed]
        val submitted = passed != null
        val totalCount = row[TaskQuizAttemptTable.totalCount]
        val subject = resolveSubject(row[PlanTaskTable.taskName], row[PlanBoardTable.id].value, attemptId)

        val questions = TaskQuizQuestionRow.find { TaskQuizQuestionTable.attemptId eq attemptId }
            .orderBy(TaskQuizQuestionTable.id to SortOrder.ASC)
            .toList()
        val choicesByQuestion = TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId inList questions.map { it.id } }
            .orderBy(TaskQuizChoiceTable.id to SortOrder.ASC)
            .groupBy { it.question.id.value }

        QuizHistoryDetailResponse(
            quizId = attemptId,
            type = "TASK",
            status = if (submitted) "DONE" else "TODO",
            planTaskId = row[TaskQuizAttemptTable.planTaskId].value,
            attemptNumber = row[TaskQuizAttemptTable.attemptNumber],
            title = row[PlanTaskTable.taskName],
            subjectId = subject?.first,
            subjectName = subject?.second,
            createdAt = row[TaskQuizAttemptTable.createdAt].toString(),
            scheduledAt = LocalDateTime.of(row[DailyPlanTable.planDate], row[PlanTaskTable.endTime]).toString(),
            completedAt = row[TaskQuizAttemptTable.submittedAt]?.toString(),
            correctCount = row[TaskQuizAttemptTable.correctCount],
            totalCount = totalCount,
            passCount = taskQuizPassCount(totalCount),
            passed = passed,
            questions = questions.map { question ->
                val choices = choicesByQuestion[question.id.value].orEmpty()
                val selected = choices.firstOrNull { it.id.value == question.selectedChoiceId }

                QuizHistoryQuestionResponse(
                    questionId = question.id.value,
                    questionText = question.questionText,
                    chapterName = question.chapterName,
                    selectedChoiceId = question.selectedChoiceId,
                    isCorrect = if (submitted) selected?.isCorrect else null,
                    explanation = if (submitted) choices.firstOrNull { it.isCorrect }?.explanation else null,
                    choices = choices.map { choice ->
                        QuizHistoryChoiceResponse(
                            id = choice.id.value,
                            choiceText = choice.choiceText,
                            selected = choice.id.value == question.selectedChoiceId,
                            isCorrect = if (submitted) choice.isCorrect else null,
                            explanation = if (submitted) choice.explanation else null
                        )
                    }
                )
            }
        )
    }

    /** 차수 하나의 과목. 트랜잭션 안에서 부른다. */
    private fun resolveSubject(taskName: String, boardId: Int, attemptId: Int): Pair<Int, String>? {
        // 교과서 id 만 필요해 벌크 조회 (Row 로 읽으면 교과서마다 lazy SELECT 가 붙는다)
        val textbookIds = PlanSubjectTable.select(PlanSubjectTable.textbookId)
            .where { PlanSubjectTable.planBoardId eq boardId }
            .map { it[PlanSubjectTable.textbookId].value }
        val chapterNames = TaskQuizQuestionTable.select(TaskQuizQuestionTable.chapterName)
            .where { (TaskQuizQuestionTable.attemptId eq attemptId) and TaskQuizQuestionTable.chapterName.isNotNull() }
            .map { it[TaskQuizQuestionTable.chapterName]!! }

        return pickSubject(
            chapterNames = chapterNames,
            chapterSubjects = chapterNameToSubjectIds(textbookIds),
            taskName = taskName,
            subjects = planBoardSubjects(boardId)
        )
    }
}

/**
 * 과목 도출. 1) 문항 단원으로 찾고 2) 못 찾으면 할일 이름 추정 3) 보드 과목이 하나뿐이면 그것.
 * 한 차수가 여러 과목에 걸치면 문항이 많은 과목을 대표로 쓴다 (동률이면 과목 id 가 작은 쪽).
 */
private fun pickSubject(
    chapterNames: List<String>,
    chapterSubjects: Map<String, List<Int>>,
    taskName: String,
    subjects: List<Pair<Int, String>>
): Pair<Int, String>? {
    val tally = HashMap<Int, Int>()
    chapterNames.forEach { chapter ->
        chapterSubjects[chapter].orEmpty().forEach { subjectId ->
            tally[subjectId] = (tally[subjectId] ?: 0) + 1
        }
    }
    val fromChapters = tally.entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.key

    val subjectId = fromChapters
        ?: guessTaskSubject(taskName, subjects)?.first
        ?: subjects.singleOrNull()?.first

    return subjects.firstOrNull { it.first == subjectId }
}

/** 차수 하나의 응시 상태. 목록 필터([QuizHistoryStatus])와 같은 기준이다 */
internal fun statusOf(passed: Boolean?, planDate: LocalDate, today: LocalDate): QuizHistoryStatus = when {
    passed != null -> QuizHistoryStatus.DONE
    planDate.isBefore(today) -> QuizHistoryStatus.MISSED
    else -> QuizHistoryStatus.TODO
}
