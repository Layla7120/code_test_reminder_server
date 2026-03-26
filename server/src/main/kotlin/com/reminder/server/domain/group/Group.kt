package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.global.BaseTimeEntity
import jakarta.persistence.*

@Entity
@Table(
    name = "`groups`",  // MySQL 예약어 충돌 방지
    uniqueConstraints = [
        UniqueConstraint(name = "uk_groups_name", columnNames = ["groupName"])
    ]
)
class Group(
    @Column(nullable = false, length = 100)
    val groupName: String,

    // BCrypt 해시값 저장 (60자 고정) — 레거시 평문 저장 버그 수정
    @Column(length = 60)
    var groupPw: String?,

    @Column(nullable = false)
    val memberMaxCount: Int = 5,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    val owner: User,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    val id: Long = 0

    // member_counter 컬럼 없음
    // 레거시 구조: member_counter += 1 → 동시 요청 시 Lost Update 발생
    // 해결: Participate COUNT()로 항상 정확한 값 계산 → 부정확할 여지 자체를 제거
    @OneToMany(mappedBy = "group", cascade = [CascadeType.ALL], orphanRemoval = true)
    val participations: MutableList<Participate> = mutableListOf()

    val memberCount: Int get() = participations.size

    // 비즈니스 규칙이 엔티티 안에 있다 → 서비스가 isFull()만 호출하면 됨
    fun isFull(): Boolean = memberCount >= memberMaxCount

    fun changePassword(encodedPw: String) {
        this.groupPw = encodedPw
    }
}
