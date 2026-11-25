package com.github.nepyh.rooter.common

import org.slf4j.Logger

/** AI 응답 파싱이 실패했을 때 같은 요청을 몇 번까지 보내는지 (처음 1번 + 다시 1번) */
const val LLM_PARSE_ATTEMPTS = 2

/**
 * AI 를 불러 응답을 파싱하고, 파싱에 실패하면 같은 요청을 한 번 더 보낸다 (#402).
 *
 * 형식 오류(수식으로 시작하는 문자열에 따옴표를 빠뜨림 등)는 대개 그 응답 한 번의 실수라 다시 부르면 맞게 온다.
 * 예전엔 한 번 실패하면 바로 0문항이 되어, 스케줄러 재시도(#318, 5분 뒤)까지 퀴즈가 비어 있었다.
 *
 * 호출 자체가 실패하면([request] 가 null) 다시 부르지 않는다 — 네트워크·타임아웃은 바로 다시 불러도 같은 경우가 많고
 * 기다리는 시간만 늘어난다. [request] 가 예외를 던지면 그대로 올라간다.
 *
 * @return 파싱한 값. 호출이 실패했거나 [LLM_PARSE_ATTEMPTS] 번 모두 파싱에 실패하면 null
 */
suspend fun <T> requestParsedWithRetry(logger: Logger, request: suspend () -> String?, parse: (String) -> T): T? {
    repeat(LLM_PARSE_ATTEMPTS) { index ->
        val content = request() ?: return null
        runCatching { parse(content) }
            .onSuccess { return it }
            .onFailure {
                logger.warn("LLM 응답 JSON 파싱 실패 ({}/{}): content={}", index + 1, LLM_PARSE_ATTEMPTS, content.take(500), it)
            }
    }
    return null
}
