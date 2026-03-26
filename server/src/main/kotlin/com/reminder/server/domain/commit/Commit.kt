package com.reminder.server.domain.commit

import com.reminder.server.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "commits",
    indexes = [Index(name = "idx_commit_date", columnList = "commitDate")]
)
class Commit(
    // LAZY 강제: EAGER는 N+1의 근원
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,

    // GitHub 원본 데이터 → 생성 이후 어떤 필드도 수정 불가
    @Column(nullable = false, updatable = false)
    val commitDate: LocalDateTime,

    @Column(nullable = false, updatable = false, length = 500)
    val commitUrl: String,

    @Column(nullable = false, updatable = false, length = 200)
    val title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    val level: CommitLevel,

    // sha: GitHub commit 고유 식별자 → DB 레벨 중복 방어
    // unique=true가 여기 있다 → 서비스 레이어 로직에 기대지 않음
    @Column(nullable = false, updatable = false, unique = true, length = 40)
    val sha: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commit_id")
    val id: Long = 0
}
