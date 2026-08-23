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

    // updatable = false 를 뺐다. 오너가 그룹을 떠나면 남은 멤버에게 승계해야 하는데,
    // 그 동작이 Flask 에는 있었고 마이그레이션에서 사라졌다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: User,
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
    // 증감은 GroupRepository 의 원자적 UPDATE 에서만 — 조건 확인과 증가가 단일 연산. 경위: docs/기록.md
    @Column(nullable = false)
    var memberCounter: Int = 0
        protected set

    fun changePassword(encodedPw: String) {
        this.groupPw = encodedPw
    }

    fun transferOwnershipTo(newOwner: User) {
        this.owner = newOwner
    }
}
