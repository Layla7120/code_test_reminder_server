package com.reminder.server.domain.history

import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HistoryService(
    private val historyRepository: HistoryRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun saveHistory(userId: Long, problemNum: String, solveTime: String): History {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        return historyRepository.save(History(user, problemNum, solveTime))
    }
}
