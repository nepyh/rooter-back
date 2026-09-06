package com.github.nepyh.rooter.module.studystyle.exception

sealed class StudyStyleValidationException(message: String) : Exception(message) {
    class EmptyAnswersException : StudyStyleValidationException("answers는 1개 이상이어야 합니다.")
    class InvalidQuestionNumberException : StudyStyleValidationException("questionNumber는 1~7 사이여야 합니다.")
    class InvalidAnswerOptionException : StudyStyleValidationException("answerOption은 1~4 사이여야 합니다. (4 = 모르겠어요)")
    class DuplicateQuestionNumberException : StudyStyleValidationException("같은 questionNumber에 대한 답변이 중복 제출되었습니다.")
}
