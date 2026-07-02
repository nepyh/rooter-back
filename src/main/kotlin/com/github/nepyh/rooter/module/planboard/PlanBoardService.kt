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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 1. DDL에 맞게 테이블명("plan_boards")과 컬럼(date) 수정!
object PlanBoards : Table("plan_boards") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") // DDL에 있는 필수 유저 외래키
    val title = varchar("title", 100)
    val startDate = date("start_date") // DDL 스펙: date
    val endDate = date("end_date")     // DDL 스펙: date
    val createdAt = datetime("created_at").default(LocalDateTime.now())

    override val primaryKey = PrimaryKey(id)
}

class PlanBoardService {

    // 전체 조회
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

    // 게시글 등록 (DDL 스펙에 맞춰 insert)
    suspend fun createBoard(request: PlanBoardCreateRequest) = newSuspendedTransaction {
        PlanBoards.insert {
            it[userId] = 1 // 💡 아직 로그인 연동 전이니 DDL default/FK 통과용으로 임시 1번 유저 세팅!
            it[title] = request.title
            it[startDate] = LocalDate.parse(request.startDate) // String -> LocalDate 파싱
            it[endDate] = LocalDate.parse(request.endDate)     // String -> LocalDate 파싱
        }
    }
}