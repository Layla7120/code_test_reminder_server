-- 랭킹 A/B 측정용 시드. @target_users 명을 만든다.
-- Python 의존성 없이 재귀 CTE 로 서버 내부에서 행을 만들어 200만 행도 수십 초면 끝난다.

-- [중요] 앱과 같은 시계를 쓴다.
-- 앱은 Asia/Seoul 로 "이번 달"을 계산하는데 MySQL 컨테이너는 UTC라,
-- KST 기준 매월 1일 0~9시에는 달이 어긋난다. 그러면 시드한 커밋이 전부
-- "지난달"로 분류되어 랭킹이 조용히 0건이 된다.
SET time_zone = '+09:00';
SET SESSION cte_max_recursion_depth = 1000000;

-- DELETE 는 200만 행에서 10분을 넘긴다(행 단위 undo 로그). TRUNCATE 는 테이블을 새로 만든다.
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE participate;
TRUNCATE TABLE commits;
TRUNCATE TABLE `groups`;
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
