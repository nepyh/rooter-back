package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectReplaceRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanSubjectNotFoundException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime

/** 보드 설명 최대 길이 (DDL 은 text 라 제한이 없어 서비스에서 막는다) */
private const val MAX_DESCRIPTION_LENGTH = 500

/** 과목 범위 등록에 필요한 엔티티 묶음 — DAO 로 그대로 연결할 수 있게 Row 타입으로 들고 다인다. */
private data class ResolvedTextbookSubject(
    val subject: SubjectRow,
    val textbook: TextbookRow,
    val startChapter: ChapterRow,
    val endChapter: ChapterRow
)

class PlanBoardService {
    /**
     * 본인 보드 목록. 최근에 만든 보드가 앞에 온다 — 클라이언트가 목록의 첫 항목을 현재 보드 컨텍스트로 쓴다.
     * [includeArchived] 가 false(기본)면 보관한 보드는 빠진다.
     */
    fun getAllBoards(userId: Int, includeArchived: Boolean = false): List<PlanBoardResponse> = transaction {
        val user = UserRow.findById(userId)
            ?: return@transaction emptyList()

        PlanBoardRow.find {
            if (includeArchived) PlanBoardTable.userId eq user.id
            else (PlanBoardTable.userId eq user.id) and (PlanBoardTable.isArchived eq false)
        }
            .orderBy(PlanBoardTable.createdAt to SortOrder.DESC)
            .map { it.toResponse() }
    }

    private fun PlanBoardRow.toResponse() = PlanBoardResponse(
        id = id.value,
        title = title,
        description = description,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        examDate = examDate?.toString(),
        examEndDate = examEndDate?.toString(),
        isArchived = isArchived,
        isRepresentative = isRepresentative,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )

    /** PATCH 에서 null 은 "변경 없음"이라 설명을 지우는 표현이 따로 필요하다 — 빈 문자열/공백만 있는 문자열을 지우기로 본다. */
    private fun normalizeDescription(description: String?): String? {
        val trimmed = description?.trim().orEmpty()
        if (trimmed.length > MAX_DESCRIPTION_LENGTH) {
            throw PlanBoardValidationException.InvalidDescriptionException()
        }
        return trimmed.ifEmpty { null }
    }

    fun createBoard(userId: Int, request: PlanBoardCreateRequest): Int {
        if (request.title.isBlank() || request.title.length > 100) {
            throw PlanBoardValidationException.InvalidTitleException()
        }

        val startDate = runCatching { LocalDate.parse(request.startDate) }
            .getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }
        val endDate = runCatching { LocalDate.parse(request.endDate) }
            .getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }
        val examDate = request.examDate?.let { parseBoardDate(it) }
        val examEndDate = request.examEndDate?.let { parseBoardDate(it) }
        requireValidExamPeriod(examDate, examEndDate)

        if (endDate.isBefore(startDate)) {
            throw PlanBoardValidationException.InvalidDateRangeException()
        }

        return transaction {
            val board = PlanBoardRow.new {
                user = UserRow[userId]
                title = request.title
                description = normalizeDescription(request.description)
                this.startDate = startDate
                this.endDate = endDate
                this.examDate = examDate
                this.examEndDate = examEndDate
                createdAt = OffsetDateTime.now()
            }
            // 대표 플랜이 하나도 없으면 이 보드를 대표로 (홈 디데이가 비어 보이지 않게)
            val hasRepresentative = PlanBoardRow.find {
                (PlanBoardTable.userId eq userId) and (PlanBoardTable.isRepresentative eq true)
            }.any()
            if (!hasRepresentative) board.isRepresentative = true
            board.id.value
        }
    }

    /**
     * 대표 플랜 설정. 유저당 1개라(DDL 부분 유니크 인덱스) 같은 트랜잭션에서 기존 대표를 먼저 내린다.
     * 홈 화면은 이 보드의 디데이를 보여준다.
     */
    fun setRepresentative(userId: Int, boardId: Int): PlanBoardResponse = transaction {
        val board = requireOwnedBoard(userId, boardId)

        PlanBoardRow.find {
            (PlanBoardTable.userId eq userId) and (PlanBoardTable.isRepresentative eq true)
        }.forEach { it.isRepresentative = false }

        board.isRepresentative = true
        board.updatedAt = OffsetDateTime.now()
        board.toResponse()
    }

    /** 대표 플랜 해제. 대표가 아니면 아무 것도 하지 않는다 (멱등) */
    fun unsetRepresentative(userId: Int, boardId: Int) = transaction {
        val board = requireOwnedBoard(userId, boardId)
        if (board.isRepresentative) {
            board.isRepresentative = false
            board.updatedAt = OffsetDateTime.now()
        }
    }

    /** title/description/startDate/endDate/examDate/examEndDate/isArchived 중 전달된 필드만 수정 (null 은 변경 없음). 본인 보드만 가능 */
    fun updateBoard(userId: Int, boardId: Int, request: PlanBoardUpdateRequest): PlanBoardResponse = transaction {
        val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
        if (board.user.id.value != userId) throw PlanBoardForbiddenException()

        request.title?.let {
            if (it.isBlank() || it.length > 100) throw PlanBoardValidationException.InvalidTitleException()
            board.title = it
        }

        val newStartDate = request.startDate?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }
        } ?: board.startDate
        val newEndDate = request.endDate?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }
        } ?: board.endDate
        if (newEndDate.isBefore(newStartDate)) throw PlanBoardValidationException.InvalidDateRangeException()
        board.startDate = newStartDate
        board.endDate = newEndDate

        // 시험 첫날·마지막 날은 바꾼 뒤의 두 값으로 함께 검증한다 (첫날만 마지막 날 뒤로 옮기는 것도 막는다)
        val newExamDate = request.examDate?.let { parseBoardDate(it) } ?: board.examDate
        val newExamEndDate = request.examEndDate?.let { parseBoardDate(it) } ?: board.examEndDate
        requireValidExamPeriod(newExamDate, newExamEndDate)
        board.examDate = newExamDate
        board.examEndDate = newExamEndDate

        request.description?.let { board.description = normalizeDescription(it) }
        request.isArchived?.let { board.isArchived = it }
        board.updatedAt = OffsetDateTime.now()

        board.toResponse()
    }

    /** 보드를 삭제하면 과목 범위/일별 계획/태스크 등 하위 데이터가 전부 cascade 삭제됨 (DDL FK 기준). 본인 보드만 가능 */
    fun deleteBoard(userId: Int, boardId: Int) = transaction {
        val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
        if (board.user.id.value != userId) throw PlanBoardForbiddenException()
        board.delete()
    }

    private fun resolveTextbookSubject(request: PlanSubjectCreateRequest): ResolvedTextbookSubject {
        val textbook = TextbookRow.findById(request.textbookId)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subject = SubjectRow.findById(textbook.subject.id.value)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val (startChapter, endChapter) = resolveChapterRange(textbook, request.startChapterId, request.endChapterId)

        return ResolvedTextbookSubject(subject, textbook, startChapter, endChapter)
    }

    private fun requireOwnedBoard(userId: Int, boardId: Int): PlanBoardRow {
        val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
        if (board.user.id.value != userId) throw PlanBoardForbiddenException()
        return board
    }

    /**
     * AI 생성(plan-generation)을 거치지 않고, 수동으로 만든 보드에 "이 기간 동안 이 교과서
     * 이 단원 범위를 공부한다"는 과목 범위를 등록한다. 태스크(plan_tasks)는 이 범위와 별개로
     * 기존 POST /plan-tasks 로 직접 추가해야 한다.
     */
    fun addSubject(userId: Int, boardId: Int, request: PlanSubjectCreateRequest): PlanSubjectResponse = transaction {
        val board = requireOwnedBoard(userId, boardId)
        val resolved = resolveTextbookSubject(request)

        val planSubject = PlanSubjectRow.new {
            planBoard = board
            textbook = resolved.textbook
            startChapter = resolved.startChapter
            endChapter = resolved.endChapter
            customRangeText = request.customRangeText
        }

        PlanSubjectResponse(
            id = planSubject.id.value,
            planBoardId = boardId,
            subjectId = resolved.subject.id.value,
            subjectName = resolved.subject.name,
            textbookId = resolved.textbook.id.value,
            textbookTitle = resolved.textbook.title,
            startChapterId = resolved.startChapter.id.value,
            endChapterId = resolved.endChapter.id.value,
            customRangeText = request.customRangeText
        )
    }

    fun getSubjects(userId: Int, boardId: Int): List<PlanSubjectResponse> = transaction {
        requireOwnedBoard(userId, boardId)

        // 조인 + 여러 테이블 컬럼 투영이라 DAO 로 표현할 수 없어 Table DSL 을 유지한다.
        (PlanSubjectTable innerJoin TextbookTable innerJoin SubjectTable)
            .selectAll()
            .where { PlanSubjectTable.planBoardId eq boardId }
            .map {
                PlanSubjectResponse(
                    id = it[PlanSubjectTable.id].value,
                    planBoardId = boardId,
                    subjectId = it[SubjectTable.id].value,
                    subjectName = it[SubjectTable.name],
                    textbookId = it[TextbookTable.id].value,
                    textbookTitle = it[TextbookTable.title],
                    startChapterId = it[PlanSubjectTable.startChapterId].value,
                    endChapterId = it[PlanSubjectTable.endChapterId].value,
                    customRangeText = it[PlanSubjectTable.customRangeText]
                )
            }
    }

    /** textbookId/startChapterId/endChapterId/customRangeText 전체를 다시 받아 통째로 교체 (부분 업데이트 아님 — 서로 연결된 값들이라 일부만 바꾸면 불일치 위험) */
    fun updateSubject(userId: Int, boardId: Int, subjectId: Int, request: PlanSubjectCreateRequest): PlanSubjectResponse = transaction {
        requireOwnedBoard(userId, boardId)
        val planSubject = PlanSubjectRow.find {
            (PlanSubjectTable.id eq subjectId) and (PlanSubjectTable.planBoardId eq boardId)
        }.firstOrNull() ?: throw PlanSubjectNotFoundException()

        val resolved = resolveTextbookSubject(request)
        planSubject.textbook = resolved.textbook
        planSubject.startChapter = resolved.startChapter
        planSubject.endChapter = resolved.endChapter
        planSubject.customRangeText = request.customRangeText

        PlanSubjectResponse(
            id = planSubject.id.value,
            planBoardId = boardId,
            subjectId = resolved.subject.id.value,
            subjectName = resolved.subject.name,
            textbookId = resolved.textbook.id.value,
            textbookTitle = resolved.textbook.title,
            startChapterId = resolved.startChapter.id.value,
            endChapterId = resolved.endChapter.id.value,
            customRangeText = request.customRangeText
        )
    }

    fun deleteSubject(userId: Int, boardId: Int, subjectId: Int) = transaction {
        requireOwnedBoard(userId, boardId)
        val planSubject = PlanSubjectRow.find {
            (PlanSubjectTable.id eq subjectId) and (PlanSubjectTable.planBoardId eq boardId)
        }.firstOrNull() ?: throw PlanSubjectNotFoundException()
        planSubject.delete()
    }

    /**
     * 이 보드의 과목 범위(교과서 목록)를 통째로 교체한다 — 수정 화면에서 교과서를 한 번에 저장하는 경로.
     * 빈 배열이면 전부 지운다. 검증을 먼저 전부 끝낸 뒤 지우고 넣기 때문에, 하나라도 잘못되면 아무 것도 바뀌지 않는다.
     */
    fun replaceSubjects(userId: Int, boardId: Int, request: PlanSubjectReplaceRequest): List<PlanSubjectResponse> = transaction {
        val board = requireOwnedBoard(userId, boardId)
        val resolved = request.subjects.map { resolveTextbookSubject(it) }

        PlanSubjectRow.find { PlanSubjectTable.planBoardId eq board.id }.forEach { it.delete() }

        resolved.mapIndexed { index, item ->
            val input = request.subjects[index]
            val planSubject = PlanSubjectRow.new {
                planBoard = board
                textbook = item.textbook
                startChapter = item.startChapter
                endChapter = item.endChapter
                customRangeText = input.customRangeText
            }

            PlanSubjectResponse(
                id = planSubject.id.value,
                planBoardId = boardId,
                subjectId = item.subject.id.value,
                subjectName = item.subject.name,
                textbookId = item.textbook.id.value,
                textbookTitle = item.textbook.title,
                startChapterId = item.startChapter.id.value,
                endChapterId = item.endChapter.id.value,
                customRangeText = input.customRangeText
            )
        }
    }
}
