-- 랭킹 A/B 측정용 시드. @target_users 명을 만든다.
-- Python 의존성 없이 재귀 CTE 로 서버 내부에서 행을 만들어 200만 행도 수십 초면 끝난다.

-- [중요] commit_date 는 UTC 로 저장한다 (server/.../global/ServiceZone).
-- 세션을 UTC 로 두어야 아래 NOW()·DATE_FORMAT 이 저장 규약과 같은 시계를 쓴다.
--
-- 예전에는 여기를 +09:00 으로 두고 "앱과 같은 시계를 쓴다"고 적어뒀는데, 그건 앱이
-- UTC 값을 KST 경계와 비교하던 시절의 우회였다. 그 어긋남 자체를 고쳤으므로(2026-08-20)
-- 시드도 저장 규약을 그대로 따른다.
SET time_zone = '+00:00';
SET SESSION cte_max_recursion_depth = 1000000;

-- DELETE 는 200만 행에서 10분을 넘긴다(행 단위 undo 로그). TRUNCATE 는 테이블을 새로 만든다.
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE participate;
TRUNCATE TABLE commits;
TRUNCATE TABLE `groups`;
TRUNCATE TABLE user_monthly_score;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- active = TRUE 여야 랭킹 쿼리(WHERE u.active = true)에 잡힌다
INSERT INTO users (github_id, nickname, repository_name, active, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < @target_users
)
SELECT CONCAT('bench_gh_', n), CONCAT('bench_nick_', n), 'bench-repo', TRUE, NOW(6), NOW(6)
FROM seq;

-- 유저마다 커밋 수를 1~40건으로 다르게 준다.
-- 전원 동일하면 DENSE_RANK 결과가 전부 1등이 되어 정렬 비용이 현실과 달라진다.
INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha)
WITH RECURSIVE seq40 AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq40 WHERE n < 40
)
SELECT
    u.user_id,
    DATE_ADD(DATE_FORMAT(NOW(), '%Y-%m-01 00:00:00'),
             INTERVAL FLOOR(RAND() * DAY(LAST_DAY(NOW()))) DAY),
    'https://github.com/bench/repo/commit/x',
    'bench problem',
    'GOLD',
    SHA1(CONCAT(u.user_id, '-', s.n))   -- 40자 hex, (user, n) 조합마다 고유
FROM users u
JOIN seq40 s ON s.n <= (u.user_id % 40) + 1;

SELECT (SELECT COUNT(*) FROM users) AS users, (SELECT COUNT(*) FROM commits) AS commits;
