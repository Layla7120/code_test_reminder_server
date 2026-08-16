package com.reminder.server.domain.rank

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 유저의 월별 커밋 수. 랭킹 점수의 진실 원천이다.
 *
 * commits 에서 다시 센 절대값만 쓴다 — 증분(+= n)은 한 번 틀리면 복구가 안 된다. 경위: docs/기록.md
 */
@Entity
@Table(name = "user_monthly_score")
@IdClass(UserMonthlyScoreId::class)
class UserMonthlyScore(
    @Id
    @Column(name = "user_id")
    val userId: Long = 0,

    // CHAR(6) 이라 JdbcTypeCode 를 명시한다 — String 기본값은 VARCHAR 라 ddl-auto=validate 가 막는다
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "score_month", length = 6)
    val scoreMonth: String = "",

    @Column(nullable = false)
    val score: Int = 0,
)

data class UserMonthlyScoreId(
    val userId: Long = 0,
    val scoreMonth: String = "",
) : Serializable

private val SCORE_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM")

fun YearMonth.toScoreMonth(): String = format(SCORE_MONTH_FORMAT)
