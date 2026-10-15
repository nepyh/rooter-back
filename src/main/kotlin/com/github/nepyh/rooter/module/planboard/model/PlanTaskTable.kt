package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

object PlanTaskTable : IntIdTable("plan_tasks") {
    // DDL 의 fk_plan_tasks_daily_plan 과 동일 (on delete cascade)
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable, onDelete = ReferenceOption.CASCADE)
    val taskName = varchar("task_name", 150)
    val startTime = time("start_time")
    val endTime = time("end_time")
    val estimatedMinutes = integer("estimated_minutes")
    val isCompleted = bool("is_completed").default(false)
    // 챗봇으로 다른 날로 미뤘을 때 원래 날짜. null = 미룬 적 없음 (rooter-ddl #25)
    val postponedFromDate = date("postponed_from_date").nullable()
    // 할일이 속한 과목 (DDL 의 fk_plan_tasks_subject 와 동일, on delete set null — rooter-ddl #32).
    // null = 과목 미지정: 복습 태스크(QuizService)·재조정 태스크(FeedbackService)·챗봇 미루기로 파생된
    // 할일(ChatService)과 컬럼 추가 전에 만든 행은 채우지 않는다 (이번 범위 밖)
    val subjectId = reference("subject_id", SubjectTable, onDelete = ReferenceOption.SET_NULL).nullable()
}

class PlanTaskRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanTaskRow>(PlanTaskTable)

    var dailyPlan by DailyPlanRow referencedOn PlanTaskTable.dailyPlanId
    var taskName by PlanTaskTable.taskName
    var startTime by PlanTaskTable.startTime
    var endTime by PlanTaskTable.endTime
    var estimatedMinutes by PlanTaskTable.estimatedMinutes
    var isCompleted by PlanTaskTable.isCompleted
    var postponedFromDate by PlanTaskTable.postponedFromDate

    // id 로만 쓰고 과목명은 서비스가 벌크 조회로 붙인다 (참조 엔티티로 두면 행마다 SELECT 가 나간다)
    var subjectId by PlanTaskTable.subjectId
}
