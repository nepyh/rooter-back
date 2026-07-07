package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardResponse
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import java.time.format.DateTimeFormatter

object PlanBoards : Table("plan_boards") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") // DDL에 있는 필수 유저 외래키
    val title = varchar("title", 100)
    val startDate = date("start_date") // DDL 스펙: date
    val endDate = date("end_date")     // DDL 스펙: date
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

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
            throw ApiException(ErrorCode.BOARD_001)
        }

        val startDate = runCatching { LocalDate.parse(request.startDate) }
            .getOrElse { throw ApiException(ErrorCode.BOARD_002) }
        val endDate = runCatching { LocalDate.parse(request.endDate) }
            .getOrElse { throw ApiException(ErrorCode.BOARD_002) }

        if (endDate.isBefore(startDate)) {
            throw ApiException(ErrorCode.BOARD_003)
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