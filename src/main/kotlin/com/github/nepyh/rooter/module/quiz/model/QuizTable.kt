package com.github.nepyh.rooter.module.quiz.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object DailyQuizQuestions : Table("daily_quiz_questions") {
    val id = integer("id").autoIncrement()
    val dailyPlanId = integer("daily_plan_id") references DailyPlans.id
    val questionText = text("question_text")

    override val primaryKey = PrimaryKey(id)
}

object DailyQuizChoices : Table("daily_quiz_choices") {
    val id = integer("id").autoIncrement()
    val questionId = integer("question_id") references DailyQuizQuestions.id
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)

    override val primaryKey = PrimaryKey(id)
}

object DailyQuizAttempts : Table("daily_quiz_attempts") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") // DDL에 있는 필수 유저 외래키
    val selectedChoiceId = integer("selected_choice_id") references DailyQuizChoices.id
    val createdAt = timestampWithTimeZone("created_at") // DB default current_timestamp, 코드에서 명시적으로 세팅

    override val primaryKey = PrimaryKey(id)
}
