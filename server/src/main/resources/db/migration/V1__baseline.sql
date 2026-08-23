-- V1 베이스라인 — 구 infra/init.sql 을 그대로 승격한 것 (2026-08-17 시점의 전체 스키마).
--
-- [이 디렉터리의 규칙 — PLAN-db-foundation.md §3]
--   * 베이스라인은 이 파일 하나. 이후 모든 변경은 V<yyyyMMddHHmmss>__<설명>.sql
--     (KST, date +%Y%m%d%H%M%S). 순번 대신 타임스탬프인 이유: 브랜치 간 번호 충돌 방지.
--   * 적용된 파일은 수정하지 않는다 — Flyway 가 체크섬으로 막는다. 고치려면 새 파일.
--   * IF NOT EXISTS 를 쓰지 않는다 — 각 파일은 정확히 한 번 실행되고,
--     "이미 있으면 넘어감"은 드리프트를 숨긴다 (init.sql 시절 user_monthly_score 가
--     기존 볼륨에 조용히 누락된 것이 그 사례).
--
-- flyway_schema_history 가 없는 기존 DB 는 baseline-on-migrate 가 "V1 과 같다"고
-- 선언하고 이 파일을 건너뛴다. 선언일 뿐 검증이 아니다 — 컬럼 수준 검증은
-- ddl-auto: validate 가 한다.
--
-- PK 컬럼명은 엔티티의 @Column(name = "...") 기준
-- 컬럼명은 Hibernate SpringPhysicalNamingStrategy (camelCase → snake_case) 기준

CREATE TABLE users (
    user_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_id       VARCHAR(100)  NOT NULL,
    nickname        VARCHAR(50)   NOT NULL,
    repository_name VARCHAR(200)  NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6),
    CONSTRAINT uk_users_github_id UNIQUE (github_id),
    CONSTRAINT uk_users_nickname  UNIQUE (nickname)
);

CREATE TABLE commits (
    commit_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    commit_date DATETIME(6)   NOT NULL,
    commit_url  VARCHAR(500)  NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    level       VARCHAR(20)   NOT NULL,
    sha         VARCHAR(40)   NOT NULL,
    CONSTRAINT uk_commits_sha UNIQUE (sha),
    INDEX idx_commit_date (commit_date),
    INDEX idx_commit_user_date (user_id, commit_date),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE `groups` (
    group_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name       VARCHAR(100) NOT NULL,
    group_pw         VARCHAR(60),
    member_max_count INT          NOT NULL DEFAULT 5,
    owner_id         BIGINT       NOT NULL,
    member_counter   INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6),
    CONSTRAINT uk_groups_name UNIQUE (group_name),
    FOREIGN KEY (owner_id) REFERENCES users(user_id)
);

-- 엔티티 테이블명: "participate" (participates 아님)
CREATE TABLE participate (
    participate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id       BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6),
    CONSTRAINT uk_participate_group_user UNIQUE (group_id, user_id),
    FOREIGN KEY (group_id) REFERENCES `groups`(group_id),
    FOREIGN KEY (user_id)  REFERENCES users(user_id)
);

-- 엔티티 테이블명: "history" (histories 아님)
-- BaseTimeEntity 미상속 → created_at, updated_at 없음
CREATE TABLE history (
    history_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    problem_num VARCHAR(20)  NOT NULL,
    solve_time  VARCHAR(10)  NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 월별 커밋 수 집계. 랭킹 조회가 commits 전체를 매번 세지 않게 한다.
-- 컬럼명이 score_month 인 이유: YEAR_MONTH 는 MySQL 예약어(INTERVAL ... YEAR_MONTH)라
-- 백틱 없이는 문법 오류가 난다.
CREATE TABLE user_monthly_score (
    user_id     BIGINT   NOT NULL,
    score_month CHAR(6)  NOT NULL,          -- 'yyyyMM'
    score       INT      NOT NULL,
    PRIMARY KEY (user_id, score_month),
    INDEX idx_rank (score_month, score DESC),
    CONSTRAINT fk_ums_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
