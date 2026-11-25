package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * 이어서 만들 AI 계획 생성 요청 (rooter-ddl `plan_generation_requests`, #405). 생성 중이거나 실패한 보드만 행이 있다.
 * 뒷 구간을 만들 때 첫 요청의 입력이 다시 필요해 [request] 에 그대로(JSON) 보관한다.
 *
 * 보드 id 가 곧 PK 인 1:1 보조 테이블이라 IntIdTable·Row 쌍 대신 Table 을 직접 쓴다.
 */
object PlanGenerationRequestTable : Table("plan_generation_requests") {
    val planBoardId = reference("plan_board_id", PlanBoardTable, onDelete = ReferenceOption.CASCADE)
    val request = text("request") // PlanGenerationRequest JSON
    val generatedUntil = date("generated_until") // 지금까지 만들어 저장한 마지막 날짜
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(planBoardId)
}
