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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// 2026-08-20 06:00 KST = 2026-08-19 21:00 UTC — 날짜가 갈리는 시각
private val FIXED_INSTANT: Instant = Instant.parse("2026-08-20T12:00:00Z")

/**
 * 증명하는 주장: "커밋 시각은 UTC 로 저장하고, 날짜·월 경계는 KST 로 판정한다"
 *
 * GitHub 이 주는 commit.author.date 는 UTC 다("...Z"). 그런데 GithubClient 는
 * 패턴 "yyyy-MM-dd'T'HH:mm:ss'Z'" 로 LocalDateTime 을 만든다 — 'Z' 가 리터럴이라
 * "UTC 다"라는 정보가 버려지고 UTC 벽시계 값만 남는다.
 *
 * 반면 Clock 은 Asia/Seoul 이라 월·일 경계는 KST 벽시계로 계산된다.
 * 두 시계가 9시간 어긋난 채 비교되고 있었다.
 *
 * 눈에 보이는 증상:
 *   - 새벽 커밋(KST 00:00~09:00)이 잔디에서 **하루 전** 칸에 찍힌다
 *   - 매월 1일 새벽 커밋이 **지난달** 점수로 들어간다
 *
 * bench/seed.sql 상단 주석이 이 문제를 이미 적어뒀다 — 벤치 스크립트에서만이 아니라
 * 운영에서도 같은 일이 일어난다.
 */
@Import(TimezoneClockConfig::class)
class CommitTimezoneTest : IntegrationTest() {

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository

    @Test
    @DisplayName("KST 새벽 커밋은 그날 잔디에 찍힌다 — 하루 전이 아니라")
    fun earlyMorningCommitLandsOnTheSameKstDay() {
        val user = userRepository.save(User("tz-grass", "tz-grass-nick", "repo"))

        // 2026-08-20 06:00 KST 에 커밋했다. UTC 로는 08-19 21:00 이다.
        commitJdbcRepository.bulkUpsert(
            listOf(commitAtUtc(user.id, LocalDateTime.of(2026, 8, 19, 21, 0), "sha-early-morning"))
        )

        val grass = commitService.getCommitGrass(user.id)["thisMonth"] ?: emptyMap()

        assertThat(grass)
            .describedAs("사용자는 8월 20일에 커밋했다 — 19일 칸에 찍히면 안 된다")
            .containsKey(LocalDate.of(2026, 8, 20))
        assertThat(grass).doesNotContainKey(LocalDate.of(2026, 8, 19))
    }

    @Test
    @DisplayName("매월 1일 KST 새벽 커밋은 이번 달로 집계된다 — 지난달이 아니라")
    fun firstOfMonthEarlyCommitCountsForThisMonth() {
        val user = userRepository.save(User("tz-month", "tz-month-nick", "repo"))

        // 2026-08-01 05:00 KST 에 커밋했다. UTC 로는 07-31 20:00 이다.
        commitJdbcRepository.bulkUpsert(
            listOf(commitAtUtc(user.id, LocalDateTime.of(2026, 7, 31, 20, 0), "sha-month-boundary"))
        )

        val grass = commitService.getCommitGrass(user.id)

        assertThat(grass["thisMonth"])
            .describedAs("KST 기준 8월 1일 커밋이므로 이번 달이다")
            .containsKey(LocalDate.of(2026, 8, 1))
        assertThat(grass["prevMonth"]).isEmpty()
    }

    /** commitDate 는 UTC 벽시계로 저장한다 — GitHub 이 주는 값 그대로다. */
    private fun commitAtUtc(userId: Long, utc: LocalDateTime, sha: String) = CommitInsertDto(
        userId = userId,
        commitDate = utc,
        commitUrl = "https://github.com/x/y/commit/$sha",
        title = "두 수의 합",
        level = CommitLevel.GOLD.name,
        sha = sha.padEnd(40, '0'),
    )
}

@TestConfiguration
class TimezoneClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Seoul"))
}
