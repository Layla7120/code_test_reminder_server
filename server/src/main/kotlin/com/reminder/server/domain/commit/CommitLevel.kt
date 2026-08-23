package com.reminder.server.domain.commit

/**
 * 커밋 메시지 대괄호 안의 난이도 표기.
 *
 * 세 사이트를 각자의 축으로 보존한다 — 서로 환산하지 않는다.
 * 경위: docs/adr/0010-난이도는-출처별-축으로-보존하고-환산하지-않는다.md
 */
enum class CommitLevel {
    // 백준 티어 (번호는 버린다 — Gold IV 와 Gold I 을 나누지 않는다)
    BRONZE, SILVER, GOLD, PLATINUM, DIAMOND, RUBY,

    // 프로그래머스 Lv.0 ~ Lv.5
    LEVEL0, LEVEL1, LEVEL2, LEVEL3, LEVEL4, LEVEL5,

    // SWEA D1 ~ D6
    D1, D2, D3, D4, D5, D6,

    // 위 세 축 어디에도 안 맞는 표기. 커밋 자체는 유효하므로 저장은 한다
    UNRATED;

    companion object {
        // "level 2" 는 공백 때문에 이름 대조로 안 잡히는 유일한 표기라 따로 푼다.
        // "Lv. 2" 도 받는다 — 프로그래머스 UI 표기가 그쪽이다.
        // SWEA 의 D3·D4 는 enum 이름과 글자가 같아서 아래 이름 대조가 그대로 잡는다.
        private val PROGRAMMERS = Regex("""^(?:lv\.?|level)\s*([0-5])$""", RegexOption.IGNORE_CASE)

        fun from(raw: String): CommitLevel {
            val token = raw.trim()
            PROGRAMMERS.find(token)?.let { return valueOf("LEVEL${it.groupValues[1]}") }

            // "Gold IV", "Silver III" 은 티어 뒤에 번호가 붙어 오므로 첫 단어만 본다.
            val head = token.split(" ")[0]
            return entries.firstOrNull { it.name.equals(head, ignoreCase = true) } ?: UNRATED
        }
    }
}
