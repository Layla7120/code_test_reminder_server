package com.reminder.server.domain.commit

// Flask 레거시에서 커밋 메시지 정규식으로 파싱하던 BOJ 티어값과 매핑
// EnumType.STRING → DB에 문자열 저장, 컬럼 순서 변경에 안전
enum class CommitLevel {
    BRONZE, SILVER, GOLD, PLATINUM, DIAMOND, RUBY, UNRATED;

    companion object {
        // 알 수 없는 레벨값은 조용히 저장하지 않고 즉시 실패
        // GitHub 커밋 메시지 파싱 실패 = 커밋 형식 자체가 잘못된 것 → 호출자가 처리해야 함
        // "Gold IV", "Silver III" 등 BOJ 티어 번호 포함 → 첫 번째 단어만 매핑
        fun from(raw: String): CommitLevel =
            entries.firstOrNull { it.name.equals(raw.trim().split(" ")[0], ignoreCase = true) }
                ?: throw IllegalArgumentException("알 수 없는 커밋 레벨: $raw")
    }
}
