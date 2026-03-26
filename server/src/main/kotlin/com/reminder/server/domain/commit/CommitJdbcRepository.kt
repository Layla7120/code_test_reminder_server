package com.reminder.server.domain.commit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class CommitInsertDto(
    val userId: Long,
    val commitDate: LocalDateTime,
    val commitUrl: String,
    val title: String,
    val level: String,   // CommitLevel.name — DB 저장 전 변환
    val sha: String,
)

@Repository
class CommitJdbcRepository(private val jdbcTemplate: JdbcTemplate) {

    // 단건 upsert 100번(네트워크 100번) → batchUpdate로 단 1번의 네트워크 I/O
    // chunkSize=100: 한 번에 100건씩 묶어서 전송
    fun bulkUpsert(commits: List<CommitInsertDto>) {
        if (commits.isEmpty()) return

        // sha 기준 오름차순 정렬 후 삽입
        // InnoDB Next-Key Lock은 인덱스 순서로 잡힘
        // 여러 스레드가 동일한 순서로 락을 요청하면 사이클이 생기지 않아 데드락 방지
        val sorted = commits.sortedBy { it.sha }

        jdbcTemplate.batchUpdate(
            """
            INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE sha = sha
            """.trimIndent(),
            sorted,
            100,
        ) { ps, commit ->
            ps.setLong(1, commit.userId)
            ps.setObject(2, commit.commitDate)
            ps.setString(3, commit.commitUrl)
            ps.setString(4, commit.title)
            ps.setString(5, commit.level)
            ps.setString(6, commit.sha)
        }
    }
}
