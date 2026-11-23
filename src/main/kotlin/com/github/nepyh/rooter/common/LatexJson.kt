package com.github.nepyh.rooter.common

/** 퀴즈 API Swagger 설명 앞에 붙이는 수식 표기 안내 (#388) */
const val QUIZ_LATEX_DOC = "[수식 표기] questionText·choices·explanation(풀이·보기별 이유)의 수식은 인라인 LaTeX 를 \$...\$ 로 감싸서 준다 " +
    "(예: \$\\frac{3}{4}\$, \$x^2\$, \$\\sqrt{2}\$). \$ 로 감싼 부분만 수식으로 그리고(KaTeX), 나머지와 수식 없는 문항은 일반 글이다. "

/**
 * 퀴즈 LLM 응답(JSON) 안의 LaTeX 역슬래시를 파싱 전에 바로잡는다 (#388).
 *
 * 퀴즈 문항의 수식은 인라인 `$...$` LaTeX 로 받는다. JSON 문자열 안에서는 역슬래시를 `\\frac` 처럼 두 번 써야 하는데,
 * AI 가 `\frac` 처럼 한 번만 쓰면
 * - `\f`·`\b`·`\n`·`\r`·`\t` 로 시작하는 명령(`\frac`, `\beta`, `\neq`, `\right`, `\times`)은 JSON escape 로 읽혀 글자가 깨지고
 * - 그 밖의 명령(`\sqrt`, `\pi`, `\cdot`)은 잘못된 escape 라 응답 전체가 파싱에 실패한다.
 *
 * 그래서 JSON 문자열 안에서
 * - `$...$` 수식 안의 역슬래시 한 개는 무조건 두 개로 바꾸고 (수식 안에 줄바꿈·탭 escape 를 쓸 일은 없다)
 * - 수식 밖에서는 JSON 이 허용하지 않는 escape(`\s` 등)만 두 개로 바꾼다 (`\n` 같은 정상 escape 는 그대로).
 * 이미 `\\` 로 쓴 것과 `\"` 는 건드리지 않는다. 문자열 밖(키·구두점)은 그대로 둔다.
 */
fun repairLatexJsonEscapes(json: String): String {
    val out = StringBuilder(json.length + 16)
    var inString = false
    var inMath = false
    var i = 0
    while (i < json.length) {
        val c = json[i]
        if (!inString) {
            if (c == '"') {
                inString = true
                inMath = false
            }
            out.append(c)
            i++
            continue
        }
        when (c) {
            '"' -> {
                inString = false
                out.append(c)
                i++
            }
            '$' -> {
                inMath = !inMath
                out.append(c)
                i++
            }
            '\\' -> {
                val next = json.getOrNull(i + 1)
                when {
                    // 이미 두 번 쓴 역슬래시, 따옴표 escape 는 그대로 (두 글자를 함께 넘긴다)
                    next == '\\' || next == '"' -> {
                        out.append(c).append(next)
                        i += 2
                    }
                    inMath -> {
                        out.append("\\\\")
                        i++
                    }
                    next != null && next in VALID_ESCAPES && (next != 'u' || isUnicodeEscape(json, i + 2)) -> {
                        out.append(c).append(next)
                        i += 2
                    }
                    else -> {
                        out.append("\\\\")
                        i++
                    }
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * 퀴즈 LLM 응답 원문을 파싱할 수 있게 다듬는다 — ```json 코드블록을 벗기고([stripCodeFence]) LaTeX 역슬래시를 바로잡는다.
 * 퀴즈 클라이언트는 코드블록을 벗기지 않아, AI 가 코드블록으로 감싸 보내면 퀴즈가 0문항이 됐다 (#388 확인 중 발견).
 */
fun cleanQuizLlmJson(content: String): String = repairLatexJsonEscapes(stripCodeFence(content))

private const val VALID_ESCAPES = "/bfnrtu"

private fun isUnicodeEscape(json: String, start: Int): Boolean =
    start + 4 <= json.length && (start until start + 4).all { json[it].isDigit() || json[it].lowercaseChar() in 'a'..'f' }
