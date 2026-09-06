package com.github.nepyh.rooter.module.studystyle

import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleAnswerResponse
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleResponse
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleSubmitRequest
import com.github.nepyh.rooter.module.studystyle.exception.StudyStyleValidationException
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswers
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val MIN_QUESTION_NUMBER = 1
private const val MAX_QUESTION_NUMBER = 7
private const val MIN_ANSWER_OPTION = 1
private const val MAX_ANSWER_OPTION = 4 // 4 = "모르겠어요"

class StudyStyleService {

    /** 제출한 문항만 기존 답변을 덮어씀 (전체 재제출을 강제하지 않음) */
    fun submitAnswers(userId: Int, request: StudyStyleSubmitRequest): StudyStyleResponse {
        if (request.answers.isEmpty()) {
            throw StudyStyleValidationException.EmptyAnswersException()
        }
        if (request.answers.map { it.questionNumber }.toSet().size != request.answers.size) {
            throw StudyStyleValidationException.DuplicateQuestionNumberException()
        }
        request.answers.forEach { answer ->
            if (answer.questionNumber !in MIN_QUESTION_NUMBER..MAX_QUESTION_NUMBER) {
                throw StudyStyleValidationException.InvalidQuestionNumberException()
            }
            if (answer.answerOption !in MIN_ANSWER_OPTION..MAX_ANSWER_OPTION) {
                throw StudyStyleValidationException.InvalidAnswerOptionException()
            }
        }

        return transaction {
            request.answers.forEach { answer ->
                StudyStyleAnswers.deleteWhere {
                    (StudyStyleAnswers.userId eq userId) and (StudyStyleAnswers.questionNumber eq answer.questionNumber.toShort())
                }
                StudyStyleAnswers.insert {
                    it[this.userId] = userId
                    it[questionNumber] = answer.questionNumber.toShort()
                    it[answerOption] = answer.answerOption.toShort()
                }
            }

            fetchAnswers(userId)
        }
    }

    fun getAnswers(userId: Int): StudyStyleResponse = transaction { fetchAnswers(userId) }

    private fun fetchAnswers(userId: Int): StudyStyleResponse {
        val answers = StudyStyleAnswers.selectAll()
            .where { StudyStyleAnswers.userId eq userId }
            .orderBy(StudyStyleAnswers.questionNumber to SortOrder.ASC)
            .map {
                StudyStyleAnswerResponse(
                    questionNumber = it[StudyStyleAnswers.questionNumber].toInt(),
                    answerOption = it[StudyStyleAnswers.answerOption].toInt()
                )
            }
        return StudyStyleResponse(answers)
    }
}
