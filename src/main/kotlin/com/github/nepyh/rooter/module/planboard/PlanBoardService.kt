package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanSubjectNotFoundException
import com.github.nepyh.rooter.module.planboard.model.Chapters
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjects
import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.planboard.model.Textbooks
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private data class ResolvedTextbookSubject(val subjectId: Int, val subjectName: String, val textbookTitle: String)

class PlanBoardService {
    fun getAllBoards(userId: Int): List<PlanBoardResponse> = transaction {
        val user = UserRow.findById(userId)
            ?: return@transaction emptyList()

        PlanBoardRow.find { PlanBoardTable.userId eq user.id }
            .map {
                PlanBoardResponse(
                    id = it.id.value,
                    title = it.title,
                    startDate = it.startDate.toString(),
                    endDate = it.endDate.toString(),
                    createdAt = it.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            }
    }

    fun createBoard(userId: Int, request: PlanBoardCreateRequest): Int {
        if (request.title.isBlank() || request.title.length > 100) {
            throw PlanBoardValidationException.InvalidTitleException()
        }

        val startDate = runCatching { LocalDate.parse(request.startDate) }
            .getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }
        val endDate = runCatching { LocalDate.parse(request.endDate) }
            .getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }

        if (endDate.isBefore(startDate)) {
            throw PlanBoardValidationException.InvalidDateRangeException()
        }

        return transaction {
            PlanBoardRow.new {
                user = UserRow[userId]
                title = request.title
                this.startDate = startDate
                this.endDate = endDate
                createdAt = OffsetDateTime.now()
            }.id.value
        }
    }

    /** title/startDate/endDate 중 전달된 필드만 수정. 본인 보드만 가능 */
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

        PlanBoardResponse(
            id = board.id.value,
            title = board.title,
            startDate = board.startDate.toString(),
            endDate = board.endDate.toString(),
            createdAt = board.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /** 보드를 삭제하면 과목 범위/일별 계획/태스크 등 하위 데이터가 전부 cascade 삭제됨 (DDL FK 기준). 본인 보드만 가능 */
    fun deleteBoard(userId: Int, boardId: Int) = transaction {
        val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
        if (board.user.id.value != userId) throw PlanBoardForbiddenException()
        board.delete()
    }

    private fun resolveTextbookSubject(request: PlanSubjectCreateRequest): ResolvedTextbookSubject {
        val textbookRow = Textbooks.selectAll().where { Textbooks.id eq request.textbookId }.firstOrNull()
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val subjectId = textbookRow[Textbooks.subjectId]
        val subjectName = Subjects.selectAll().where { Subjects.id eq subjectId }.firstOrNull()?.get(Subjects.name)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

        val startOrder = Chapters.selectAll().where { Chapters.id eq request.startChapterId }.firstOrNull()
            ?.get(Chapters.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        val endOrder = Chapters.selectAll().where { Chapters.id eq request.endChapterId }.firstOrNull()
            ?.get(Chapters.chapterOrder)
            ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
        if (startOrder > endOrder) throw PlanBoardValidationException.InvalidSubjectRangeException()

        return ResolvedTextbookSubject(subjectId, subjectName, textbookRow[Textbooks.title])
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
        requireOwnedBoard(userId, boardId)
        val resolved = resolveTextbookSubject(request)

        val id = PlanSubjects.insert {
            it[planBoardId] = boardId
            it[textbookId] = request.textbookId
            it[this.startChapterId] = request.startChapterId
            it[this.endChapterId] = request.endChapterId
            it[customRangeText] = request.customRangeText
        } get PlanSubjects.id

        PlanSubjectResponse(
            id = id,
            planBoardId = boardId,
            subjectId = resolved.subjectId,
            subjectName = resolved.subjectName,
            textbookId = request.textbookId,
            textbookTitle = resolved.textbookTitle,
            startChapterId = request.startChapterId,
            endChapterId = request.endChapterId,
            customRangeText = request.customRangeText
        )
    }

    fun getSubjects(userId: Int, boardId: Int): List<PlanSubjectResponse> = transaction {
        requireOwnedBoard(userId, boardId)

        (PlanSubjects innerJoin Textbooks innerJoin Subjects)
            .selectAll()
            .where { PlanSubjects.planBoardId eq boardId }
            .map {
                PlanSubjectResponse(
                    id = it[PlanSubjects.id],
                    planBoardId = boardId,
                    subjectId = it[Subjects.id],
                    subjectName = it[Subjects.name],
                    textbookId = it[Textbooks.id],
                    textbookTitle = it[Textbooks.title],
                    startChapterId = it[PlanSubjects.startChapterId],
                    endChapterId = it[PlanSubjects.endChapterId],
                    customRangeText = it[PlanSubjects.customRangeText]
                )
            }
    }

    /** textbookId/startChapterId/endChapterId/customRangeText 전체를 다시 받아 통째로 교체 (부분 업데이트 아님 — 서로 연결된 값들이라 일부만 바꾸면 불일치 위험) */
    fun updateSubject(userId: Int, boardId: Int, subjectId: Int, request: PlanSubjectCreateRequest): PlanSubjectResponse = transaction {
        requireOwnedBoard(userId, boardId)
        PlanSubjects.selectAll()
            .where { (PlanSubjects.id eq subjectId) and (PlanSubjects.planBoardId eq boardId) }
            .firstOrNull() ?: throw PlanSubjectNotFoundException()

        val resolved = resolveTextbookSubject(request)

        PlanSubjects.update({ (PlanSubjects.id eq subjectId) and (PlanSubjects.planBoardId eq boardId) }) {
            it[textbookId] = request.textbookId
            it[startChapterId] = request.startChapterId
            it[endChapterId] = request.endChapterId
            it[customRangeText] = request.customRangeText
        }

        PlanSubjectResponse(
            id = subjectId,
            planBoardId = boardId,
            subjectId = resolved.subjectId,
            subjectName = resolved.subjectName,
            textbookId = request.textbookId,
            textbookTitle = resolved.textbookTitle,
            startChapterId = request.startChapterId,
            endChapterId = request.endChapterId,
            customRangeText = request.customRangeText
        )
    }

    fun deleteSubject(userId: Int, boardId: Int, subjectId: Int) = transaction {
        requireOwnedBoard(userId, boardId)
        val deleted = PlanSubjects.deleteWhere { (PlanSubjects.id eq subjectId) and (PlanSubjects.planBoardId eq boardId) }
        if (deleted == 0) throw PlanSubjectNotFoundException()
    }
}
