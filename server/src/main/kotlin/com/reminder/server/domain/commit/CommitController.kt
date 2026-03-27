package com.reminder.server.domain.commit

import org.springframework.web.bind.annotation.*
import java.time.LocalDate

data class FetchCommitsRequest(val userId: Long)

@RestController
@RequestMapping("/commits")
class CommitController(private val commitService: CommitService) {

    // GitHub에서 커밋 가져와 저장
    @PostMapping
    fun fetchAndSave(@RequestBody req: FetchCommitsRequest): Map<String, Int> =
        mapOf("saved" to commitService.fetchAndSaveCommits(req.userId))

    // 최근 7일 커밋한 날짜 목록
    @GetMapping("/activity")
    fun getWeeklyActivity(@RequestParam userId: Long): Map<String, List<LocalDate>> =
        mapOf("dates" to commitService.getWeeklyActivity(userId))

    // 난이도별 커밋 수
    @GetMapping("/level")
    fun getLevelDistribution(@RequestParam userId: Long): Map<String, Long> =
        commitService.getLevelDistribution(userId)

    // 이번달 + 저번달 잔디 데이터
    @GetMapping("/grass")
    fun getCommitGrass(@RequestParam userId: Long) =
        commitService.getCommitGrass(userId)
}
