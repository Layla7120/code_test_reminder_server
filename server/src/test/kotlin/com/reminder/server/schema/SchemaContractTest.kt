package com.reminder.server.schema

import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 증명하는 주장: "실제로 떠 있는 스키마가 ADR 이 진술한 것과 같다"
 *
 * 판정 근거는 마이그레이션 SQL 이 아니라 `information_schema` 다.
 * SQL 을 파싱하면 동어반복이 된다 — Flyway 가 그 SQL 을 실행해 이 DB 를 만들었으니
 * 항상 통과한다. 경위: docs/adr/0008-검증을-앵커와-게이트로-한다.md
 *
 * 아래 표들은 스키마의 파생 사본이 아니라 **독립적인 제2진술**이다.
 * 사본이면 하나여야 하지만 독립 진술은 둘이어야 의미가 있다 — 어긋남 자체가 정보다.
 * 특히 participate 와 groups 의 RESTRICT 는 의도적 선택인데 마이그레이션 파일만 보면
 * ON DELETE 절을 빠뜨린 것과 구별이 안 된다. 여기서 진술하면 나중에 누가 편의로
 * CASCADE 를 걸 때 빨간불이 나고 이유가 적혀 있다.
 */
class SchemaContractTest : IntegrationTest() {

    @Autowired lateinit var jdbc: JdbcTemplate

    /**
     * FK 삭제 규칙. 근거: docs/adr/0002-cascade-는-파생값의-원천이-아닐-때만-건다.md
     *
     * MySQL 은 ON DELETE 절이 없는 FK 를 RESTRICT 가 아니라 NO ACTION 으로 보고한다
     * (InnoDB 에서 동작은 같다). 기대값이 NO ACTION 인 것은 실제 출력에 맞춘 게 아니라
     * "삭제 규칙을 걸지 않았다"는 의도의 MySQL 표기다.
     */
    private val expectedDeleteRules = mapOf(
        // 유저에 종속된 소유 데이터. 이 행을 원천 삼는 것이 없다
        "fk_commits_user" to "CASCADE",
        "fk_history_user" to "CASCADE",
        "fk_ums_user" to "CASCADE",
        // groups.member_counter 가 participate 에서 파생된다.
        // CASCADE 면 GroupService.leaveGroup 을 거치지 않고 행이 사라져 카운터가 어긋난다
        "fk_participate_user" to "NO ACTION",
        // 그룹이 사라지는 경로도 leaveGroup 하나로 유지한다
        "fk_participate_group" to "NO ACTION",
        // CASCADE 면 오너 삭제가 그룹을 통째로 지운다
        "fk_groups_owner" to "NO ACTION",
    )

    @Test
    @DisplayName("FK 의 삭제 규칙이 ADR-0002 의 표와 정확히 일치한다")
    fun foreignKeyDeleteRules() {
        val actual = jdbc.queryForList(
            """
            SELECT CONSTRAINT_NAME, DELETE_RULE
            FROM information_schema.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE()
            """,
        ).associate { it["CONSTRAINT_NAME"] as String to it["DELETE_RULE"] as String }

        // containsAllEntriesOf 가 아니라 완전 일치다 — 새 FK 가 진술 없이 들어오면 깨져야 한다
        assertThat(actual).isEqualTo(expectedDeleteRules)
    }

    @Test
    @DisplayName("users 에 active 컬럼이 없다")
    fun usersHasNoActiveColumn() {
        assertThat(columnsOf("users")).doesNotContainKey("active")
    }

    @Test
    @DisplayName("history.solve_time 은 int 이고 solved_at 은 NOT NULL 이다")
    fun historyColumnTypes() {
        val columns = columnsOf("history")

        assertThat(columns["solve_time"]?.dataType).isEqualTo("int")
        assertThat(columns["solve_time"]?.nullable).isFalse()
        assertThat(columns["solved_at"]?.dataType).isEqualTo("datetime")
        assertThat(columns["solved_at"]?.nullable).isFalse()
    }

    @Test
    @DisplayName("uk_history_user_problem 이 (user_id, problem_num) 유니크 제약으로 존재한다")
    fun historyUniqueKey() {
        val columns = jdbc.queryForList(
            """
            SELECT COLUMN_NAME
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'history'
              AND INDEX_NAME = 'uk_history_user_problem'
              AND NON_UNIQUE = 0
            ORDER BY SEQ_IN_INDEX
            """,
        ).map { it["COLUMN_NAME"] as String }

        assertThat(columns).containsExactly("user_id", "problem_num")
    }

    private data class Column(val dataType: String, val nullable: Boolean)

    private fun columnsOf(table: String): Map<String, Column> =
        jdbc.queryForList(
            """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
            """,
            table,
        ).associate {
            it["COLUMN_NAME"] as String to Column(
                dataType = it["DATA_TYPE"] as String,
                nullable = it["IS_NULLABLE"] == "YES",
            )
        }
}
