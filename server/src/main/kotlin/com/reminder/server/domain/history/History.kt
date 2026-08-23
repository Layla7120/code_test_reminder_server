package com.reminder.server.domain.history

import com.reminder.server.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 한 행 = 한 유저가 푼 한 문제. 재도전은 행을 늘리지 않고 이 행을 덮는다.
 *
 * uk_history_user_problem 이 그 규칙을 DB 에서 지킨다 — 앱이 빠뜨려도 같은 문제가
 * 두 줄로 뜨지 않는다. 경위: docs/adr/0005-history-를-살리고-문제당-한-행으로-정의한다.md
 */
@Entity
@Table(name = "history")
class History(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,

    @Column(nullable = false, length = 20, updatable = false)
    val problemNum: String,

    solveTime: Int,
    solvedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    val id: Long = 0

    /** 초 단위. "01:05:00" 같은 표시 형식은 클라이언트가 만든다. */
    @Column(nullable = false)
    var solveTime: Int = solveTime
        protected set

    /** UTC 로 저장한다 — 시간 정책의 출처는 global/ServiceZone 하나다. */
    @Column(nullable = false)
    var solvedAt: LocalDateTime = solvedAt
        protected set

    // setter 직접 노출 대신 의도가 드러나는 메서드로 상태 변경 통제
    fun updateSolve(solveTime: Int, solvedAt: LocalDateTime) {
        this.solveTime = solveTime
        this.solvedAt = solvedAt
    }
}
