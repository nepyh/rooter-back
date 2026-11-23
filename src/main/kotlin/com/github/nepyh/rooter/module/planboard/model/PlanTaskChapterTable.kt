package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * 할일이 다루는 단원 (rooter-ddl `plan_task_chapters`, #387). 행이 없으면 단원 미지정.
 * 앱에서 떨어진 단원을 여러 개 고를 수 있어(1단원·3단원) plan_tasks 의 start/end 컬럼이 아니라 별도 테이블이다.
 *
 * (plan_task_id, chapter_id) 복합 PK 인 연결 테이블이라 IntIdTable·Row 쌍으로 표현할 수 없어 Table 을 직접 쓴다.
 */
object PlanTaskChapterTable : Table("plan_task_chapters") {
    val planTaskId = reference("plan_task_id", PlanTaskTable, onDelete = ReferenceOption.CASCADE)
    val chapterId = reference("chapter_id", ChapterTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(planTaskId, chapterId)
}
