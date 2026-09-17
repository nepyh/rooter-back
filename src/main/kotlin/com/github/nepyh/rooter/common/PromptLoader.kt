package com.github.nepyh.rooter.common

/**
 * resources 아래 prompts 디렉터리의 md 파일로 분리해 둔 LLM 프롬프트 원문을 읽어서 {{자리표시자}}를 치환한다.
 * 프롬프트 문구만 수정할 땐 재컴파일 없이 md 파일만 고치면 되고, diff 도 코드가 아니라
 * 프롬프트 문장 자체로 남는다.
 */
object PromptLoader {
    fun load(resourcePath: String, vararg replacements: Pair<String, String>): String {
        val template = PromptLoader::class.java.classLoader.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("프롬프트 리소스를 찾을 수 없습니다: $resourcePath")

        return replacements.fold(template) { text, (key, value) ->
            text.replace("{{$key}}", value)
        }
    }
}
