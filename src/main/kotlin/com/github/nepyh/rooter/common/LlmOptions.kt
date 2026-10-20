package com.github.nepyh.rooter.common

import kotlinx.serialization.Serializable

/**
 * DeepSeek chat/completions 요청의 thinking 옵션.
 * deepseek-flash 는 기본으로 thinking 이 켜져 있어서, 계획 생성처럼 출력이 긴 요청은 생각 토큰만 수천 개를 쓰고
 * 응답이 2배 넘게 느려진다 (7일·3과목 계획: thinking on 16.9초 / off 7.4초). 우리 프롬프트는 JSON 형식만
 * 지키면 되므로 끈다. Json 의 encodeDefaults 가 꺼져 있어 기본값으로 두면 요청에 실리지 않으니, 요청마다 명시해서 넘긴다.
 */
@Serializable
data class LlmThinking(val type: String) {
    companion object {
        val DISABLED = LlmThinking("disabled")
    }
}
