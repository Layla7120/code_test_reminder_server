CREATE DATABASE IF NOT EXISTS reminder CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reminder;

-- PK 컬럼명은 엔티티의 @Column(name = "...") 기준
-- 컬럼명은 Hibernate SpringPhysicalNamingStrategy (camelCase → snake_case) 기준

CREATE TABLE IF NOT EXISTS users (
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

CREATE TABLE IF NOT EXISTS commits (
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

CREATE TABLE IF NOT EXISTS `groups` (
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
CREATE TABLE IF NOT EXISTS participate (
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
CREATE TABLE IF NOT EXISTS history (
    history_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    problem_num VARCHAR(20)  NOT NULL,
    solve_time  VARCHAR(10)  NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 월별 커밋 수 집계. 랭킹 조회가 commits 전체를 매번 세지 않게 한다.
-- 컬럼명이 score_month 인 이유: YEAR_MONTH 는 MySQL 예약어(INTERVAL ... YEAR_MONTH)라
-- 백틱 없이는 문법 오류가 난다.
CREATE TABLE IF NOT EXISTS user_monthly_score (
    user_id     BIGINT   NOT NULL,
    score_month CHAR(6)  NOT NULL,          -- 'yyyyMM'
    score       INT      NOT NULL,
    PRIMARY KEY (user_id, score_month),
    INDEX idx_rank (score_month, score DESC),
    CONSTRAINT fk_ums_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
