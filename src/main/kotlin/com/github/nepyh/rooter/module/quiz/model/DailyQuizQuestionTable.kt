package com.github.nepyh.rooter.module.quiz.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object DailyQuizQuestionTable : IntIdTable("daily_quiz_questions") {
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable)
    val questionText = text("question_text")
    val explanation = text("explanation").nullable() // 풀이 과정 — 이 컬럼 추가 전에 만든 퀴즈는 NULL (rooter-ddl #19)
    val chapterName = varchar("chapter_name", 150).nullable() // 문제의 학습 범위 단원 (단원별 오답률 기준). 범위 밖·예전 퀴즈는 NULL (rooter-ddl #27)
}

class DailyQuizQuestionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyQuizQuestionRow>(DailyQuizQuestionTable)

    var dailyPlan by DailyPlanRow referencedOn DailyQuizQuestionTable.dailyPlanId
    var questionText by DailyQuizQuestionTable.questionText
    var explanation by DailyQuizQuestionTable.explanation
    var chapterName by DailyQuizQuestionTable.chapterName
}
