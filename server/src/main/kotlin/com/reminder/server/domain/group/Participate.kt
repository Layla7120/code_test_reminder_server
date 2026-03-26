package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.global.BaseTimeEntity
import jakarta.persistence.*

@Entity
@Table(
    name = "participate",
    uniqueConstraints = [
        // 복합키의 의미("한 유저는 한 그룹에 한 번만")는 UNIQUE 제약으로 보존
        // PK는 대리키로 단순화 → JPA 영속성 컨텍스트 관리 안정화
        UniqueConstraint(name = "uk_participate_group_user", columnNames = ["group_id", "user_id"])
    ]
)
class Participate(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    val group: Group,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participate_id")
    val id: Long = 0
}
