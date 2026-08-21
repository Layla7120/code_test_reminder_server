-- 탈퇴를 소프트 삭제에서 물리 삭제로 단일화한다.
-- 근거: docs/adr/0001-사용자-삭제를-물리-삭제로-단일화한다.md
--       docs/adr/0002-cascade-는-파생값의-원천이-아닐-때만-건다.md
--
-- 되돌릴 수 없다. active 컬럼이 사라지면 "탈퇴했지만 행은 남은 유저"라는 상태가
-- 표현 불가능해진다. 실사용자가 없는 지금이 이 변경이 싼 유일한 시점이다.
--
-- FK 를 갈라서 거는 이유 — CASCADE 는 그 행이 다른 파생값의 원천이 아닐 때만 안전하다:
--   * commits / history / user_monthly_score → CASCADE. 유저에 종속된 소유 데이터고,
--     이 행들을 원천 삼는 것이 없다.
--   * participate.user_id → 유지. groups.member_counter 가 이 행들에서 파생된다.
--     CASCADE 로 걸면 GroupService.leaveGroup 을 거치지 않고 행이 사라져 카운터가
--     실제 인원보다 큰 채로 남는다. 유지하면 leaveGroup 을 빠뜨렸을 때 FK 위반으로
--     즉시 터진다 — 조용한 손상 대신 시끄러운 실패다.
--   * groups.owner_id → 유지. CASCADE 면 오너 삭제가 그룹을 통째로 지운다.
--
-- 베이스라인의 FK 는 이름 없이 만들어져 MySQL 이 <table>_ibfk_N 을 붙였다.
-- 재생성하면서 fk_ 접두사 이름을 준다 — SchemaContractTest 가 이름으로 대조하고,
-- 백틱 게이트(ADR-0008)가 fk_ 접두사 식별자의 실재를 검사한다.

ALTER TABLE commits
    DROP FOREIGN KEY commits_ibfk_1,
    ADD CONSTRAINT fk_commits_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE;

ALTER TABLE history
    DROP FOREIGN KEY history_ibfk_1,
    ADD CONSTRAINT fk_history_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE;

-- fk_ums_user 만 두 문장으로 나눈다. 이름이 이미 있어서, 한 ALTER 안에서 DROP 하고
-- 같은 이름으로 ADD 하면 MySQL 이 ERROR 1826 Duplicate foreign key constraint name 을 낸다
-- (같은 문장 안에서는 삭제가 아직 반영되지 않는다).
ALTER TABLE user_monthly_score DROP FOREIGN KEY fk_ums_user;
ALTER TABLE user_monthly_score
    ADD CONSTRAINT fk_ums_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE;

-- participate 와 groups 는 이름만 준다. 삭제 규칙은 바꾸지 않는다 (RESTRICT 유지).
-- MySQL 은 ON DELETE 절이 없는 FK 를 information_schema 에 NO ACTION 으로 보고한다.
ALTER TABLE participate
    DROP FOREIGN KEY participate_ibfk_1,
    ADD CONSTRAINT fk_participate_group
        FOREIGN KEY (group_id) REFERENCES `groups` (group_id);

ALTER TABLE participate
    DROP FOREIGN KEY participate_ibfk_2,
    ADD CONSTRAINT fk_participate_user
        FOREIGN KEY (user_id) REFERENCES users (user_id);

ALTER TABLE `groups`
    DROP FOREIGN KEY groups_ibfk_1,
    ADD CONSTRAINT fk_groups_owner
        FOREIGN KEY (owner_id) REFERENCES users (user_id);

-- 마지막에 지운다. 위 FK 재생성이 users 를 참조하므로 순서가 중요하다.
ALTER TABLE users
    DROP COLUMN active;
