CREATE DATABASE IF NOT EXISTS reminder CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reminder;

CREATE TABLE IF NOT EXISTS users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_id       VARCHAR(100) NOT NULL,
    nickname        VARCHAR(100) NOT NULL,
    repository_name VARCHAR(200) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT uk_users_github_id UNIQUE (github_id),
    CONSTRAINT uk_users_nickname  UNIQUE (nickname)
);

CREATE TABLE IF NOT EXISTS commits (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    sha         VARCHAR(40) NOT NULL,
    level       VARCHAR(20) NOT NULL,
    problem     VARCHAR(200),
    commit_date DATE NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_commits_sha UNIQUE (sha),
    INDEX idx_commit_user_date (user_id, commit_date),
    INDEX idx_commit_date (commit_date)
);

CREATE TABLE IF NOT EXISTS `groups` (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name       VARCHAR(100) NOT NULL,
    group_pw         VARCHAR(200),
    member_counter   INT NOT NULL DEFAULT 0,
    member_max_count INT NOT NULL DEFAULT 5,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_groups_name UNIQUE (group_name)
);

CREATE TABLE IF NOT EXISTS participates (
    participate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id       BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT uk_participate UNIQUE (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS histories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    problem_num VARCHAR(50),
    solve_time  VARCHAR(50),
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL
);
