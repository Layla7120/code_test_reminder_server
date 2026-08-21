package com.reminder.server.domain.commit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 증명하는 주장: "백준·프로그래머스·SWEA 표기가 각자의 축으로 살아남는다"
 *
 * 이 갈래가 조용히 틀리면 아무도 모른다 — 커밋은 정상 저장되고 개수도 맞고,
 * 난이도 하나만 UNRATED 로 뭉친다. 실제로 그렇게 96건이 뭉쳐 있었다.
 * 경위: docs/adr/0010-난이도는-출처별-축으로-보존하고-환산하지-않는다.md
 *
 * Spring 컨텍스트도 컨테이너도 필요 없다.
 */
class CommitLevelTest {

    @ParameterizedTest(name = "[{0}] → {1}")
    @CsvSource(
        // 백준 — 티어 번호는 버리고 티어만 남긴다
        "Gold IV, GOLD", "Gold I, GOLD", "Silver III, SILVER", "Bronze V, BRONZE",
        "Platinum IV, PLATINUM", "Diamond II, DIAMOND", "Ruby V, RUBY",
        // 프로그래머스
        "level 1, LEVEL1", "level 2, LEVEL2", "level 3, LEVEL3", "level 4, LEVEL4",
        "level 0, LEVEL0", "level 5, LEVEL5",
        // SWEA
        "D3, D3", "D4, D4", "D1, D1", "D6, D6",
        // 표기 흔들림 — 대소문자, 공백, 프로그래머스 UI 의 "Lv." 표기
        "LEVEL2, LEVEL2", "Level 2, LEVEL2", "Lv. 2, LEVEL2", "Lv2, LEVEL2",
        "d4, D4", "GOLD IV, GOLD", "'  level 2  ', LEVEL2",
    )
    fun parsesEachSite(raw: String, expected: CommitLevel) {
        assertThat(CommitLevel.from(raw)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{0}] → UNRATED")
    @ValueSource(
        strings = [
            "Master",      // 백준 최상위 티어. 세 사이트 축 밖이다
            "level 9",     // 프로그래머스에 없는 레벨
            "D9",          // SWEA 에 없는 난이도
            "Easy",        // 다른 사이트
            "",
        ],
    )
    fun unknownNotationFallsBackToUnrated(raw: String) {
        assertThat(CommitLevel.from(raw)).isEqualTo(CommitLevel.UNRATED)
    }

    /**
     * 회귀 앵커 — 2026-08-21 에 layla7120/Algorithms 에서 실제로 관측한 대괄호 값 21종과
     * 그 빈도다. 커밋 191건 중 패턴에 맞은 188건이고, 고치기 전에는 이 중 96건이
     * UNRATED 로 뭉쳐 있었다 (`GET /commits/level` 이 UNRATED: 96 을 돌려줬다).
     *
     * "잘 되게 고쳤다"가 아니라 그때 그 입력으로 이 표가 나오는가를 묻는다.
     */
    @Test
    @DisplayName("실측한 대괄호 값 21종 188건이 고친 뒤의 분포와 정확히 일치한다")
    fun reproducesMeasuredDistribution() {
        val measured = mapOf(
            "level 2" to 47, "Gold IV" to 23, "level 3" to 18, "level 1" to 18,
            "Gold III" to 11, "Gold V" to 8, "Gold I" to 7, "Silver I" to 7,
            "Bronze V" to 7, "Gold II" to 6, "Platinum V" to 6, "D4" to 6,
            "level 4" to 4, "Bronze III" to 4, "Silver III" to 3, "Silver IV" to 3,
            "Platinum IV" to 3, "D3" to 3, "Bronze II" to 2, "Silver V" to 1,
            "Platinum III" to 1,
        )

        val distribution = measured.entries
            .groupingBy { CommitLevel.from(it.key) }
            .fold(0) { sum, entry -> sum + entry.value }

        assertThat(distribution).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                CommitLevel.GOLD to 55,
                CommitLevel.LEVEL2 to 47,
                CommitLevel.LEVEL1 to 18,
                CommitLevel.LEVEL3 to 18,
                CommitLevel.SILVER to 14,
                CommitLevel.BRONZE to 13,
                CommitLevel.PLATINUM to 10,
                CommitLevel.D4 to 6,
                CommitLevel.LEVEL4 to 4,
                CommitLevel.D3 to 3,
            ),
        )
        assertThat(distribution.values.sum()).isEqualTo(188)
        assertThat(distribution)
            .describedAs("실측값 중에는 세 사이트 밖 표기가 없었다 — UNRATED 가 남으면 갈래가 빠진 것")
            .doesNotContainKey(CommitLevel.UNRATED)
    }
}
