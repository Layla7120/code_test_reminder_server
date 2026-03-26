package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.global.BaseTimeEntity
import jakarta.persistence.*

@Entity
@Table(
    name = "`groups`",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_groups_name", columnNames = ["groupName"])
    ]
)
class Group(
    @Column(nullable = false, length = 100)
    val groupName: String,

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

    // member_counter 복원 — 단, 애플리케이션에서 += 1 하지 않음
    //
    // [이전 접근의 문제]
    // participations.size → LAZY 컬렉션 전체 로딩 → OOM
    // COUNT 쿼리 + INSERT → TOCTOU 레이스 컨디션 (check-then-act 비원자적)
    //
    // [현재 접근]
    // DB 레벨 원자적 UPDATE: member_counter = member_counter + 1 WHERE counter < max
    // 조건 확인과 증가가 단일 연산 → Lost Update 없음, 엔티티 로딩 없음
    // 반환값 0 = 정원 초과, 1 = 성공 → 별도 조회 불필요
    @Column(nullable = false)
    var memberCounter: Int = 0
        protected set

    fun changePassword(encodedPw: String) {
        this.groupPw = encodedPw
    }
}
