package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class PlanBoardService {
    suspend fun getAllBoards(userId: Int): List<PlanBoardResponse> = newSuspendedTransaction {
        val user = UserRow.findById(userId)
            ?: return@newSuspendedTransaction emptyList()

        PlanBoardRow.find { PlanBoardTable.userId eq user.id }
            .map {
                PlanBoardResponse(
                    id = it.id.value,
                    title = it.title,
                    content = "시작: ${it.startDate}, 종료: ${it.endDate}", // DTO 호환용 임시 처리
                    createdAt = it.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            }
    }

    suspend fun createBoard(userId: Int, request: PlanBoardCreateRequest): Int {
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
            PlanBoardRow.new {
                user = UserRow[userId]
                title = request.title
                this.startDate = startDate
                this.endDate = endDate
                createdAt = OffsetDateTime.now()
            }.id.value
        }
    }
}
