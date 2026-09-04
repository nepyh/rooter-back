package com.github.nepyh.rooter.module.leveltest.model

import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 없는 테이블: 생성-제출 2단계 흐름을 위해 문항/보기/시도를 저장할 곳이 필요해서 추가.
// rooter-ddl 에는 최종 채점 결과를 담는 level_test_results 만 존재함 (반영 필요, 별도 전달함).
object LevelTestAttempts : IntIdTable("level_test_attempts") {
    val userId = reference("user_id", UserTable)
    val grade = integer("grade")
    val referenceGradeLabel = varchar("reference_grade_label", 30)
    val isSubmitted = bool("is_submitted").default(false)
    val createdAt = timestampWithTimeZone("created_at")
}

object LevelTestQuestions : Table("level_test_questions") {
    val id = integer("id").autoIncrement()
    val attemptId = integer("attempt_id").references(LevelTestAttempts.id)
    val subjectId = integer("subject_id").references(Subjects.id)
    val questionText = text("question_text")

    override val primaryKey = PrimaryKey(id)
}

object LevelTestChoices : Table("level_test_choices") {
    val id = integer("id").autoIncrement()
    val questionId = integer("question_id").references(LevelTestQuestions.id)
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)
    val explanation = text("explanation")

    override val primaryKey = PrimaryKey(id)
}

// DDL 에 이미 존재하는 테이블 (feature/everything-ddl 기준): 과목별 최종 점수만 저장, 등급(상/중/하)은
// score 로부터 매번 계산하는 파생값이라 컬럼으로 저장하지 않음.
object LevelTestResults : Table("level_test_results") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UserTable.id)
    val subjectId = integer("subject_id").references(Subjects.id)
    val score = integer("score") // 0~100 정답률(%). 시도마다 문항 수가 달라질 수 있어 원시 정답 개수가 아닌 비율로 저장
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
