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

        // 한 번에 치환한다 — 차례로 바꾸면 앞에서 넣은 값(학생 메시지·대화 이력) 안의 "{{USER_MESSAGE}}" 같은 글자가
        // 뒤 단계에서 다시 치환된다. 넘기지 않은 자리표시자는 그대로 둔다
        val values = replacements.toMap()
        return PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }
    }

    private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)}}""")
}
