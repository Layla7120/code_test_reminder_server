-- 랭킹 A/B 측정용 규모별 시드
--
-- 사용법:
--   docker exec -i reminder-mysql mysql -ureminder -preminder reminder \
--     -e "SET @target_users = 10000; SOURCE /dev/stdin" < bench/seed_scale.sql
--   (실무적으로는 bench/run_benchmark.sh 가 대신 호출한다)
--
-- 설계 의도:
--   Python/pymysql 의존성 없이 순수 SQL로만 시드한다. 재귀 CTE로 서버 내부에서
--   행을 만들어 네트워크 왕복이 없어, 200만 행도 수십 초면 끝난다.
--
--   커밋 수를 유저마다 다르게(1~40건) 주는 이유:
--   전원 동일하면 DENSE_RANK 결과가 전부 1등이 되어 정렬 비용이 현실과 달라진다.
--   user_id % 40 으로 결정론적 분포를 만들어 동점자도 자연스럽게 생기게 한다.

-- [중요] 앱과 같은 시계를 쓴다.
-- 앱은 Clock.system(ZoneId.of("Asia/Seoul")) 로 "이번 달"을 계산하는데,
-- MySQL 컨테이너는 UTC라 KST 기준 매월 1일 0~9시 구간에서 달이 어긋난다.
-- 이 줄이 없으면 그 시간대에 시드한 커밋이 전부 "지난달" 로 분류되어
-- 랭킹이 전부 0건으로 나오고, 측정이 조용히 무의미해진다.
SET time_zone = '+09:00';

SET SESSION cte_max_recursion_depth = 1000000;

-- 이전 회차 데이터 제거.
-- DELETE 대신 TRUNCATE 를 쓴다 — 200만 행 규모에서 DELETE 는 행 단위로 undo 로그를 쌓아
-- 10분을 넘기고, 뒤이은 INSERT 까지 purge 경합으로 느려진다. TRUNCATE 는 테이블을 새로 만든다.
-- FK 가 걸려 있어 TRUNCATE 가 거부되므로 잠시 검사를 끈다 (벤치마크 전용 DB 기준).
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE participate;
TRUNCATE TABLE commits;
TRUNCATE TABLE `groups`;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- 유저 @target_users 명
-- active = TRUE 여야 랭킹 쿼리(WHERE u.active = true)에 잡힌다.
-- (docs/스키마-재설계.md 의 deactivated_at 안은 아직 미적용 — 현행 init.sql 기준)
INSERT INTO users (github_id, nickname, repository_name, active, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_users
)
SELECT
    CONCAT('bench_gh_', n),
    CONCAT('bench_nick_', n),
    'bench-repo',
    TRUE,
    NOW(6),
    NOW(6)
FROM seq;

-- 유저당 1~40건의 이번 달 커밋
-- commit_date 는 반드시 이번 달 안에 들어와야 랭킹 쿼리에 집계된다
INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha)
WITH RECURSIVE seq40 AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq40 WHERE n < 40
)
SELECT
    u.user_id,
    DATE_ADD(
        DATE_FORMAT(NOW(), '%Y-%m-01 00:00:00'),
        INTERVAL FLOOR(RAND() * DAY(LAST_DAY(NOW()))) DAY
    ),
    'https://github.com/bench/repo/commit/x',
    'bench problem',
    'GOLD',
    SHA1(CONCAT(u.user_id, '-', s.n))   -- 40자 hex, (user, n) 조합마다 고유
FROM users u
JOIN seq40 s ON s.n <= (u.user_id % 40) + 1;

SELECT
    (SELECT COUNT(*) FROM users)   AS seeded_users,
    (SELECT COUNT(*) FROM commits) AS seeded_commits;
