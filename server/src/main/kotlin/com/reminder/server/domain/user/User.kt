package com.reminder.server.domain.user

import com.reminder.server.global.BaseTimeEntity
import jakarta.persistence.*

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_nickname", columnNames = ["nickname"])
    ]
)
class User(
    @Column(name = "github_id", nullable = false, length = 100)
    val githubId: String,

    @Column(nullable = false, length = 50)
    var nickname: String,

    @Column(nullable = false, length = 200)
    var repositoryName: String,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    val id: Long = 0

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    // setter 직접 노출 대신 의도가 드러나는 메서드로 상태 변경 통제
    fun updateProfile(nickname: String, repositoryName: String) {
        this.nickname = nickname
        this.repositoryName = repositoryName
    }

    fun deactivate() {
        this.active = false
    }
}
