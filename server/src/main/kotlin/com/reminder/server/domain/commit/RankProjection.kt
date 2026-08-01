package com.reminder.server.domain.commit

import java.time.LocalDateTime

// native query 결과를 받는 인터페이스
// JPA가 쿼리 결과 컬럼명과 getter 이름을 매핑해줌
//
// 응답(RankResponse)이 쓰는 세 개만 둔다.
// nickname·githubId·previousMonthCount는 아무도 읽지 않으면서
// 쿼리에는 GROUP BY 부담과 조인 범위 확대를 강요하고 있었다.
interface RankProjection {
    fun getUserId(): Long
    fun getCurrentMonthCount(): Long
    fun getRank(): Long
}

interface UserRankProjection {
    fun getRank(): Long
    fun getCurrentMonthCount(): Long
}

interface MemberCommitProjection {
    fun getUserId(): Long
    fun getNickname(): String
    fun getCurrentMonthCount(): Long
    fun getPreviousMonthCount(): Long
    fun getRank(): Long
}

interface CommitSummaryProjection {
    fun getCommitDate(): LocalDateTime
    fun getLevel(): CommitLevel
}
