-- 월별 점수를 KST 기준으로 다시 센다.
--
-- 앞선 백필(V20260819212939)은 DATE_FORMAT(commit_date, '%Y%m') 으로 월을 뽑았다.
-- commit_date 는 UTC 인데 그걸 그대로 포맷해서, KST 기준 매월 1일 00:00~09:00 에 한
-- 커밋이 지난달 점수로 들어가 있다. 경위: server/.../global/ServiceZone
--
-- 앞의 마이그레이션을 고치지 않고 새로 추가하는 이유: 이미 적용된 파일을 바꾸면
-- Flyway 체크섬이 깨진다. 교정은 언제나 다음 마이그레이션으로 한다.
--
-- 절대값 재계산이라 몇 번을 돌려도 결과가 같다. 다만 월이 바뀌면서 비는 행이 생길 수
-- 있으므로(7월 커밋이 8월로 옮겨가 7월 행이 남는 경우) 먼저 지우고 다시 만든다.
-- 비활성 유저는 행을 갖지 않는다는 불변조건도 여기서 함께 지킨다.

DELETE FROM user_monthly_score;

INSERT INTO user_monthly_score (user_id, score_month, score)
SELECT
    c.user_id,
    DATE_FORMAT(CONVERT_TZ(c.commit_date, '+00:00', '+09:00'), '%Y%m'),
    COUNT(*)
FROM commits c
JOIN users u ON u.user_id = c.user_id AND u.active = TRUE
GROUP BY c.user_id, DATE_FORMAT(CONVERT_TZ(c.commit_date, '+00:00', '+09:00'), '%Y%m');
