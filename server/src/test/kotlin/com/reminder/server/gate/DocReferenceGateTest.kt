package com.reminder.server.gate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 증명하는 주장: "현재·미래 층 문서와 .kt 주석이 가리키는 것이 실제로 있다"
 *
 * 판정은 scripts/check-doc-refs.sh 가 한다. 여기서는 그 스크립트를 빌드에 태우기만 한다 —
 * 게이트를 별도 Gradle 태스크로 빼지 않고 테스트로 만드는 이유는
 * docs/adr/0008-검증을-앵커와-게이트로-한다.md 에 있다.
 *
 * 로직을 Kotlin 으로 옮겨 적지 않은 이유: 손으로 돌려볼 수 있는 스크립트 하나가
 * 진실 원천이어야 한다. 두 벌로 두면 둘이 어긋난다.
 */
class DocReferenceGateTest {

    @Test
    @DisplayName("문서와 주석의 백틱 식별자·마크다운 링크가 전부 실재한다")
    fun docReferencesExist() {
        val result = runGate("scripts/check-doc-refs.sh")

        assertThat(result.exitCode)
            .describedAs("실재하지 않는 참조가 있다:\n${result.output}")
            .isZero()
    }
}
