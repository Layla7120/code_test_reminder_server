package com.reminder.server.domain.commit

import com.reminder.server.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "commits",
    indexes = [Index(name = "idx_commit_date", columnList = "commitDate")],
    uniqueConstraints = [
        // 유일성 범위는 유저 한 명이다 — 포크 저장소는 유저끼리 같은 sha 를 갖는다.
        UniqueConstraint(name = "uk_commits_user_sha", columnNames = ["user_id", "sha"])
    ]
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

    // 유일성은 (user_id, sha) 복합 제약이 건다 — 위 @Table 참고
    @Column(nullable = false, updatable = false, length = 40)
    val sha: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commit_id")
    val id: Long = 0
}
