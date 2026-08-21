package com.reminder.server.gate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 증명하는 주장: "선언됐지만 아무도 부르지 않는 함수가 없다"
 *
 * 판정은 scripts/check-dead-code.sh 가 한다. 자세한 제외 규칙과
 * 왜 함수만 보는지는 그 스크립트 머리에 적혀 있다.
 *
 * 이 게이트가 처음 잡은 것: CommitRepository.existsBySha 는 호출이 0회인 데다
 * 의미도 틀려 있었다 — 4ee76dc 가 sha 유일성을 (user_id, sha) 로 좁혔는데
 * 이 함수는 전역 sha 로 조회한다. 살아 있었다면 남의 커밋에 true 를 반환한다.
 */
class DeadCodeGateTest {

    @Test
    @DisplayName("호출되지 않는 함수가 없다")
    fun noUncalledFunctions() {
        val result = runGate("scripts/check-dead-code.sh")

        assertThat(result.exitCode)
            .describedAs("죽은 코드가 있다:\n${result.output}")
            .isZero()
    }
}
