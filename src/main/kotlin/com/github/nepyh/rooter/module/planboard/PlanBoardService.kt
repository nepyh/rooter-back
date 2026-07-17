package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PlanBoardService {
    suspend fun getAllBoards(): List<PlanBoardResponse> = newSuspendedTransaction {
        PlanBoards.selectAll().map {
            PlanBoardResponse(
                id = it[PlanBoards.id],
                title = it[PlanBoards.title],
                content = "시작: ${it[PlanBoards.startDate]}, 종료: ${it[PlanBoards.endDate]}", // DTO 호환용 임시 처리
                createdAt = it[PlanBoards.createdAt].format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
    }

    suspend fun createBoard(request: PlanBoardCreateRequest): Int {
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

        return newSuspendedTransaction {
            PlanBoards.insert {
                it[userId] = 1
                it[title] = request.title
                it[this.startDate] = startDate
                it[this.endDate] = endDate
            } get PlanBoards.id
        }
    }
}
