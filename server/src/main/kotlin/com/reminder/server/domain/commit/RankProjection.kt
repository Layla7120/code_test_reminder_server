package com.reminder.server.domain.commit

import java.time.LocalDateTime

// native query 결과를 받는 인터페이스
// JPA가 쿼리 결과 컬럼명과 getter 이름을 매핑해줌

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
