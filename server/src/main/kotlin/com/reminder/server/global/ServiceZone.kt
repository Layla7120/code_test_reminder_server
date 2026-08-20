package com.reminder.server.global

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 이 서비스의 시간 정책.
 *
 *   저장은 UTC. 날짜·월 판정은 KST.
 *
 * commit_date 는 GitHub 이 준 값(UTC)을 그대로 담는다. DATETIME 은 타임존을 갖지 않으므로
 * "이 컬럼은 UTC 다"는 코드와 문서로만 유지되는 약속이다 — 여기가 그 약속의 유일한 출처다.
 *
 * 판정을 KST 로 하는 이유: 사용자가 "오늘 풀었다"고 여기는 기준이 KST 다. 새벽 2시 커밋은
 * 사용자에게 오늘이지 어제가 아니다. UTC 로 판정하면 00:00~09:00 KST 커밋이 전부 하루 전으로
 * 밀린다 — CommitTimezoneTest 가 그 상황을 고정한다.
 *
 * 그래서 경계는 항상 두 단계다: KST 로 경계를 잡고 → UTC 로 바꿔서 쿼리한다.
 */
object ServiceZone {

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** KST 벽시계 기준 오늘 */
    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** KST 벽시계 기준 이번 달 */
    fun currentMonth(clock: Clock): YearMonth = YearMonth.now(clock.withZone(ZONE))

    /** KST 자정을 UTC 벽시계로 — commit_date 와 비교할 수 있는 값 */
    fun startOfDayUtc(date: LocalDate): LocalDateTime =
        date.atStartOfDay(ZONE).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()

    /** KST 기준 그 달 1일 자정을 UTC 벽시계로 */
    fun startOfMonthUtc(month: YearMonth): LocalDateTime = startOfDayUtc(month.atDay(1))

    /** 저장된 UTC 값을 사용자에게 보여줄 KST 날짜로 */
    fun toKstDate(utc: LocalDateTime): LocalDate =
        utc.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate()

    /** 저장된 UTC 값이 속한 KST 기준 월 */
    fun toKstMonth(utc: LocalDateTime): YearMonth = YearMonth.from(toKstDate(utc))
}
