package com.reminder.server.domain.commit

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

// 8월 1일 자정 — 월 경계 바로 앞뒤 커밋을 만들기 위한 기준 시각
private val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 8, 1, 0, 0, 0)

/**
 * 증명하는 주장: "NOW() 하드코딩을 Clock 빈 주입으로 바꿔 특정 시점을 재현할 수 있다"
 *
 * Flask 레거시는 모듈 로드 시점의 TODAY 상수를 썼다 — 테스트가 불가능했다.
 * Kotlin 전환의 핵심 성과 중 하나가 Clock을 빈으로 주입해 Clock.fixed()로 특정 시점을
 * 재현 가능하게 만든 것이었는데, 정작 이걸 실제로 확인하는 테스트가 없었다.
 *
 * ClockConfig의 운영 Clock(Clock.system(...))을 이 테스트에서만 Clock.fixed(...)로 교체하고,
 * 월 경계 바로 앞뒤(7월 31일 23:59:59 / 8월 1일 00:00:00)에 커밋을 심어
 * getCommitGrass()가 실제로 "지금"을 이 고정 시각 기준으로 계산하는지 확인한다.
 */
@Import(FixedClockConfig::class)
class ClockInjectionTest : IntegrationTest() {

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository

    @Test
    @DisplayName("고정된 Clock 기준으로 월 경계 커밋이 이번달/저번달로 정확히 나뉜다")
    fun commitsAreBucketedByTheFixedClockNotRealTime() {
        val user = userRepository.save(User("clock-test", "clock-nick", "repo"))

        val lastSecondOfJuly = FIXED_NOW.minusSeconds(1)   // 2026-07-31T23:59:59 → 저번달
        val firstSecondOfAugust = FIXED_NOW                // 2026-08-01T00:00:00 → 이번달

        commitJdbcRepository.bulkUpsert(listOf(
            commitDto(user.id, lastSecondOfJuly, "sha-prev-month"),
            commitDto(user.id, firstSecondOfAugust, "sha-this-month"),
        ))

        val grass = commitService.getCommitGrass(user.id)

        assertThat(grass.getValue("thisMonth")).containsKey(firstSecondOfAugust.toLocalDate())
        assertThat(grass.getValue("prevMonth")).containsKey(lastSecondOfJuly.toLocalDate())
    }

    private fun commitDto(userId: Long, date: LocalDateTime, sha: String) = CommitInsertDto(
        userId = userId,
        commitDate = date,
        commitUrl = "https://example.com/$sha",
        title = "테스트 문제",
        level = CommitLevel.GOLD.name,
        sha = sha.padEnd(40, '0'),
    )
}

@TestConfiguration
class FixedClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(
        FIXED_NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        ZoneId.of("Asia/Seoul"),
    )
}
