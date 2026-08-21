package com.reminder.server.support

import org.springframework.jdbc.core.JdbcTemplate

/**
 * commits 로부터 user_monthly_score 를 다시 계산하는 테스트 fixture.
 *
 * 운영에서 이 일을 하는 건 CommitService 의 recompute 인데, 테스트는 커밋을 SQL 로 직접
 * 넣으므로(POST /commits 는 MockGithubClient 를 탄다) 그 경로를 안 거친다.
 * 그래서 같은 계산을 여기서 한 번 돌려준다.
 *
 * 쓰는 SQL 은 백필 마이그레이션(V20260819212939)에서 users 조인만 뺀 것이다. 그 조인은
 * 탈퇴자를 거르는 용도였는데, 이제 탈퇴자는 commits 행 자체가 CASCADE 로 사라진다.
 *
 * 마이그레이션과 같은 SQL 을 쓰는 이유: 테스트가 직접 점수를 써넣으면 "커밋이 진실 원천이고
 * 점수는 파생"이라는 관계가 fixture 에서 사라진다. 파생 규칙이 틀리면 테스트도 같이 틀려야 한다.
 */
fun JdbcTemplate.recomputeAllScores() {
    update(
        """
        INSERT INTO user_monthly_score (user_id, score_month, score)
        SELECT c.user_id, DATE_FORMAT(c.commit_date, '%Y%m'), COUNT(*)
        FROM commits c
        GROUP BY c.user_id, DATE_FORMAT(c.commit_date, '%Y%m')
        ON DUPLICATE KEY UPDATE score = VALUES(score)
        """.trimIndent(),
    )
}
