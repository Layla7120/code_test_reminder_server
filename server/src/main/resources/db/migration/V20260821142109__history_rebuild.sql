-- history 를 "문제당 한 행"으로 다시 정의한다.
-- 근거: docs/adr/0005-history-를-살리고-문제당-한-행으로-정의한다.md
--
-- 되돌릴 수 없다. 기존 행을 지우고 스키마를 바꾼다.
-- 근거: solved_at 을 NOT NULL 로 넣어야 하는데 기존 행에 그 값을 만들 근거가 없다.
--       마이그레이션 시각으로 백필하면 없는 사실을 지어내는 것이고, 그 시각으로 정렬한
--       화면은 거짓을 보여준다. 실사용자가 없고 이 기능은 미완성이라 지금 데이터는
--       개발 중 넣어본 값뿐이다.
DELETE FROM history;

-- solve_time: VARCHAR(10) "HH:MM:SS" → 초 단위 INT.
-- 문자열이면 "1:05:00"·"01:05:00"·"65분"을 DB 가 전부 받아들이고 정렬·평균·비교가 안 된다.
-- 위 DELETE 로 테이블이 비어 있으므로 변환할 값이 없다.
--
-- solved_at: 신규. 저장은 UTC (global/ServiceZone 이 그 정책의 유일한 출처).
--
-- FK 는 손대지 않는다 — V20260821135217 이 이미 fk_history_user 를 ON DELETE CASCADE 로
-- 재생성했다 (ADR-0002).
ALTER TABLE history
    MODIFY solve_time INT NOT NULL,
    ADD COLUMN solved_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT uk_history_user_problem UNIQUE (user_id, problem_num);
