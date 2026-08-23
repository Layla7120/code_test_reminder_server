-- sha 의 유일성 범위를 전 세계 → 유저 한 명으로 좁힌다.
--
-- git sha 는 내용 주소라, 저장소를 포크하거나 같은 템플릿에서 갈라지면 서로 다른 유저가
-- 같은 sha 를 정당하게 갖는다. 코테 스터디에서 포크는 흔하다.
--
-- 전역 UNIQUE(sha) 였을 때의 증상: 두 번째 유저의 커밋이 bulkUpsert 의
-- ON DUPLICATE KEY UPDATE 에 걸려 조용히 사라진다. 예외도 로그도 없고 saved 만 0 이다.
-- CommitShaScopeTest 가 이 상황을 고정한다 (고치기 전 expected:1 but was:0).
--
-- 기존 데이터는 옮길 필요가 없다. 전역 UNIQUE 를 통과한 행들은 (user_id, sha) 에서도
-- 자동으로 유일하다 — 제약이 느슨해지는 방향이라 위반이 생길 수 없다.

ALTER TABLE commits
    DROP INDEX uk_commits_sha,
    ADD CONSTRAINT uk_commits_user_sha UNIQUE (user_id, sha);
