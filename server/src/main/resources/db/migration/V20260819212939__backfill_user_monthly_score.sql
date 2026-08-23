-- 기존 commits 행에 대한 월별 점수 백필.
--
-- 이 마이그레이션이 필요한 이유: 785ee3c 가 user_monthly_score 를 추가한 뒤로 새로 수집한
-- 커밋만 점수에 반영됐다. 그 이전 커밋은 집계된 적이 없어서, 읽기 경로를 집계 테이블로
-- 돌리는 순간(Phase 3) 과거 커밋이 통째로 랭킹에서 사라진다.
--
-- PLAN-aggregate-table.md §3-5 는 "마이그레이션 도구가 없으므로 일회성 SQL 로 실행하고
-- 명령을 보고서에 남긴다"고 적었다. Flyway 도입(714e049)으로 그 전제가 없어져 마이그레이션
-- 으로 넣는다 — 손으로 실행하는 절차가 사라지고, 어느 DB 에 적용됐는지가 기록에 남는다.
--
-- 여기서는 users 를 조인해도 된다. 일회성이라 매 요청 비용이 아니다.
-- 비활성 유저를 제외하는 건 §3-6 의 불변조건("행이 있다 = 랭킹에 포함된다")을 세우는 쪽이다.
--
-- ON DUPLICATE KEY UPDATE: 이미 집계된 달(785ee3c 이후 수집분)을 덮어써도 안전하다.
-- 절대값이라 몇 번을 실행해도 결과가 같다.
INSERT INTO user_monthly_score (user_id, score_month, score)
SELECT c.user_id, DATE_FORMAT(c.commit_date, '%Y%m'), COUNT(*)
FROM commits c
JOIN users u ON u.user_id = c.user_id AND u.active = TRUE
GROUP BY c.user_id, DATE_FORMAT(c.commit_date, '%Y%m')
ON DUPLICATE KEY UPDATE score = VALUES(score);
