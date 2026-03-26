package com.reminder.server.domain.history

import com.reminder.server.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface HistoryRepository : JpaRepository<History, Long> {
    fun findByUser(user: User): List<History>
}
