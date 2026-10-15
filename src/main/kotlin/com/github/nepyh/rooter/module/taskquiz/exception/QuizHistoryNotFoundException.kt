package com.github.nepyh.rooter.module.taskquiz.exception

/** 본인 소유가 아니거나 없는 퀴즈 차수 (퀴즈 히스토리 상세 조회) */
class QuizHistoryNotFoundException : Exception("존재하지 않거나 본인 소유가 아닌 퀴즈입니다.")
