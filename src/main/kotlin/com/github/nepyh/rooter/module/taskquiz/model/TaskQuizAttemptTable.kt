package com.github.nepyh.rooter.module.taskquiz.model

import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 태스크 종료 시각에 자동으로 뜨는 "완료 확인 퀴즈".
// 기존 daily_quiz_* (일일 자가 테스트, 유저가 직접 생성)와는 별개 기능이라 테이블도 분리함.
object TaskQuizAttemptTable : IntIdTable("task_quiz_attempts") {
    val planTaskId = reference("plan_task_id", PlanTaskTable)
    val attemptNumber = integer("attempt_number") // 1 = 최초, 2~3 = 재시도 (최대 2회)
    val totalCount = integer("total_count")
    val correctCount = integer("correct_count").nullable() // null = 아직 채점 전(미제출)
    val passed = bool("passed").nullable() // null = 미제출, true/false = 채점 결과 (5문항 중 4개 이상 = true)
    val createdAt = timestampWithTimeZone("created_at")
}

class TaskQuizAttemptRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TaskQuizAttemptRow>(TaskQuizAttemptTable)

    // 이 테이블의 FK 는 id 로만 쓰여서(재시도 대상 스캔) 참조 엔티티 로드 없이 id 로 접근한다.
    var planTaskId by TaskQuizAttemptTable.planTaskId
    var attemptNumber by TaskQuizAttemptTable.attemptNumber
    var totalCount by TaskQuizAttemptTable.totalCount
    var correctCount by TaskQuizAttemptTable.correctCount
    var passed by TaskQuizAttemptTable.passed
    var createdAt by TaskQuizAttemptTable.createdAt
}
