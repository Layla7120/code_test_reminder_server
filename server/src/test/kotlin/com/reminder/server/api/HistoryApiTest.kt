package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * 증명하는 주장: "history 의 한 행은 유저 한 명이 푼 문제 하나다"
 *
 * 이 테이블은 오래 쓰기 전용이었다 — POST 만 있고 읽는 코드가 0개였다.
 * 조회를 만들면서 한 행의 의미를 문제당 하나로 못박았고, 그 의미가 HTTP 로
 * 보이는 곳이 여기다. 경위: docs/adr/0005-history-를-살리고-문제당-한-행으로-정의한다.md
 */
class HistoryApiTest : ApiTest() {

    @Test
    @DisplayName("기록을 남기면 201 과 함께 problemNum·solveTime·solvedAt 이 돌아온다")
    fun saveReturnsTheRecordedShape() {
        val userId = createUser("hist-shape")

        val res = save(userId, "1000", 65)

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(res.body)
            .contains("\"problemNum\":\"1000\"")
            .contains("\"solveTime\":65")
            .contains("\"solvedAt\"")
        assertThat(res.body)
            .describedAs("historyId 는 내보내지 않는다 — 클라이언트가 행을 지목할 키는 문제 번호다")
            .doesNotContain("historyId")
    }

    @Test
    @DisplayName("조회는 최근에 푼 문제부터 내려온다")
    fun listIsSortedByMostRecentlySolved() {
        val userId = createUser("hist-sort")
        save(userId, "1000", 60)
        save(userId, "1001", 61)
        save(userId, "1002", 62)

        val body = get("/history?userId=$userId").body ?: error("본문이 비어 있다")

        assertThat(problemNums(body))
            .describedAs("solvedAt DESC 가 아니면 화면에 오래된 문제가 위에 뜬다")
            .containsExactly("1002", "1001", "1000")
    }

    @Test
    @DisplayName("같은 문제를 다시 등록하면 행이 늘지 않고 갱신된다")
    fun resolvingTheSameProblemUpdatesInPlace() {
        val userId = createUser("hist-upsert")
        save(userId, "1000", 300)
        save(userId, "1001", 100)

        save(userId, "1000", 120)

        val body = get("/history?userId=$userId").body ?: error("본문이 비어 있다")
        assertThat(problemNums(body))
            .describedAs("행이 늘면 같은 문제가 화면에 두 번 뜬다 — uk_history_user_problem 이 막아야 한다")
            .containsExactly("1000", "1001")
        assertThat(body)
            .describedAs("갱신이면 마지막에 낸 기록이 남는다")
            .contains("\"solveTime\":120")
            .doesNotContain("\"solveTime\":300")
    }

    @Test
    @DisplayName("다른 유저의 기록은 섞이지 않는다")
    fun listIsScopedToOneUser() {
        val mine = createUser("hist-mine")
        val other = createUser("hist-other")
        save(mine, "1000", 60)
        save(other, "2000", 60)

        assertThat(problemNums(get("/history?userId=$mine").body ?: "")).containsExactly("1000")
    }

    @Test
    @DisplayName("기록이 없는 유저는 빈 배열")
    fun emptyHistoryIsAnEmptyArray() {
        val userId = createUser("hist-empty")

        assertThat(get("/history?userId=$userId").body?.trim()).isEqualTo("[]")
    }

    @Test
    @DisplayName("문제 번호가 비어 있으면 400")
    fun blankProblemNumIsRejected() {
        val userId = createUser("hist-blank")

        val res = save(userId, "   ", 60)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body).contains("problemNum")
    }

    @Test
    @DisplayName("풀이 시간이 0 이하면 400")
    fun nonPositiveSolveTimeIsRejected() {
        val userId = createUser("hist-zero")

        assertThat(save(userId, "1000", 0).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(save(userId, "1000", -5).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    @DisplayName("없는 유저로 기록하거나 조회하면 404")
    fun unknownUserIsNotFound() {
        assertThat(save(999_999, "1000", 60).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/history?userId=999999").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("userId 파라미터가 없으면 400")
    fun missingUserIdIsBadRequest() {
        assertThat(get("/history").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun save(userId: Long, problemNum: String, solveTime: Int) =
        post("/history", """{"userId":$userId,"problemNum":"$problemNum","solveTime":$solveTime}""")

    /** 응답 배열에서 problemNum 을 나온 순서대로 뽑는다 — 정렬 자체가 검증 대상이라 순서를 지킨다. */
    private fun problemNums(body: String): List<String> =
        Regex("\"problemNum\"\\s*:\\s*\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
}
