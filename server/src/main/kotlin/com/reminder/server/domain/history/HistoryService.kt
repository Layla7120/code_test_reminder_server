package com.reminder.server.domain.history

import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.ServiceZone
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class HistoryService(
    private val historyRepository: HistoryRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    /**
     * 같은 문제를 다시 등록하면 행을 늘리지 않고 갱신한다.
     *
     * 먼저 찾아보고 없으면 넣는 방식이라 동시 요청 두 개가 함께 통과할 수 있다 —
     * 그때 두 번째는 uk_history_user_problem 에 걸려 409 로 끝난다(GlobalExceptionHandler).
     * 같은 사람이 같은 문제를 동시에 두 번 제출하는 경우라 재시도로 충분하다.
     */
    @Transactional
    fun saveHistory(userId: Long, problemNum: String, solveTime: Int): History {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val trimmed = problemNum.trim()
        val solvedAt = ServiceZone.nowUtc(clock)

        val existing = historyRepository.findByUserAndProblemNum(user, trimmed)
            ?: return historyRepository.save(History(user, trimmed, solveTime, solvedAt))

        existing.updateSolve(solveTime, solvedAt)
        return existing
    }

    @Transactional(readOnly = true)
    fun getHistory(userId: Long): List<History> {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        return historyRepository.findByUserOrderBySolvedAtDesc(user)
    }
}
