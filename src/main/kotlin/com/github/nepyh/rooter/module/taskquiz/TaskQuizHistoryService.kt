package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.module.planboard.chapterNameToSubjectIds
import com.github.nepyh.rooter.module.planboard.guessTaskSubject
import com.github.nepyh.rooter.module.planboard.planBoardSubjects
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryItemResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryListResponse
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDateTime

/** 퀴즈 히스토리 목록의 한 페이지 크기 (복습 목록과 같게 50) */
const val QUIZ_HISTORY_PAGE_SIZE = 50

/** 응시 상태 필터. TODO = 아직 채점 전(미응시), DONE = 채점이 끝난 퀴즈 */
enum class QuizHistoryStatus { TODO, DONE }

/**
 * 퀴즈 히스토리 조회. 조회 전용이라 LLM 호출도 상태 변경도 없다.
 * 지금은 태스크 완료 확인 퀴즈(차수 = 1항목)만 담고, 일일 퀴즈는 사양이 확정되면 같은 응답에 type=DAILY 로 합류한다.
 */
class TaskQuizHistoryService {

    /** 본인 퀴즈를 최신순으로 limit 개. cursor 는 앞 페이지 응답의 nextCursor (id 커서라 조회 중 새 퀴즈가 생겨도 안 밀린다). */
    fun list(
        userId: Int,
        status: QuizHistoryStatus? = null,
        cursor: Int? = null,
        limit: Int = QUIZ_HISTORY_PAGE_SIZE
    ): QuizHistoryListResponse = transaction {
        // 소유자·상태·커서 조건을 한 번에 걸고 제목·날짜·시각까지 같이 읽으려면 조인이 필요해 Table DSL 을 쓴다
        fun condition(after: Int?): Op<Boolean> {
            var cond = PlanBoardTable.userId eq userId
            if (status == QuizHistoryStatus.TODO) cond = cond and TaskQuizAttemptTable.passed.isNull()
            if (status == QuizHistoryStatus.DONE) cond = cond and TaskQuizAttemptTable.passed.isNotNull()
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

        /**
         * 과목 도출. 1) 문항 단원으로 찾고 2) 못 찾으면 할일 이름 추정 3) 보드 과목이 하나뿐이면 그것.
         * 한 차수가 여러 과목에 걸치면 문항이 많은 과목을 대표로 쓴다 (동률이면 과목 id 가 작은 쪽).
         */
        fun subjectOf(row: ResultRow): Pair<Int, String>? {
            val boardId = row[PlanBoardTable.id].value
            val chapterSubjects = chapterSubjectsByBoard[boardId].orEmpty()

            val tally = HashMap<Int, Int>()
            chapterNamesByAttempt[row[TaskQuizAttemptTable.id].value].orEmpty().forEach { chapter ->
                chapterSubjects[chapter].orEmpty().forEach { subjectId ->
                    tally[subjectId] = (tally[subjectId] ?: 0) + 1
                }
            }
            val fromChapters = tally.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
                ?.key

            val subjects = boardSubjects[boardId].orEmpty()
            val subjectId = fromChapters
                ?: guessTaskSubject(row[PlanTaskTable.taskName], subjects)?.first
                ?: subjects.singleOrNull()?.first

            return subjects.firstOrNull { it.first == subjectId }
        }

        QuizHistoryListResponse(
            quizzes = page.map { row ->
                val passed = row[TaskQuizAttemptTable.passed]
                val subject = subjectOf(row)

                QuizHistoryItemResponse(
                    quizId = row[TaskQuizAttemptTable.id].value,
                    type = "TASK",
                    status = if (passed == null) "TODO" else "DONE",
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
}
