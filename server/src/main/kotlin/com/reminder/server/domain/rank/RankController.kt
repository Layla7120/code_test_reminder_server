package com.reminder.server.domain.rank

import org.springframework.web.bind.annotation.*

data class RankResponse(val userId: Long, val commitCount: Long, val rank: Long)
fun RankEntry.toResponse() = RankResponse(userId, commitCount, rank)

@RestController
@RequestMapping("/rank")
class RankController(private val rankService: RankService) {

    @GetMapping
    fun getTop30(): List<RankResponse> =
        rankService.getTop30().map { it.toResponse() }

    @GetMapping("/users")
    fun getUserRank(@RequestParam userId: Long): Map<String, Long?> =
        mapOf("rank" to rankService.getUserRank(userId))
}
