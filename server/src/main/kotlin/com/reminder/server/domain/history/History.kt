package com.reminder.server.domain.history

import com.reminder.server.domain.user.User
import jakarta.persistence.*

@Entity
@Table(name = "history")
class History(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,

    @Column(nullable = false, length = 20)
    val problemNum: String,

    // 레거시: "HH:MM:SS" 문자열 — 하위호환 리스크로 1차 마이그레이션에서 타입 변환 보류
    @Column(nullable = false, length = 10)
    val solveTime: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    val id: Long = 0
}
