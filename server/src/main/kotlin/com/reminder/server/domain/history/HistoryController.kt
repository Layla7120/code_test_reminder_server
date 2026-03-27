package com.reminder.server.domain.history

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class SaveHistoryRequest(val userId: Long, val problemNum: String, val solveTime: String)

@RestController
@RequestMapping("/history")
class HistoryController(private val historyService: HistoryService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun saveHistory(@RequestBody req: SaveHistoryRequest) =
        historyService.saveHistory(req.userId, req.problemNum, req.solveTime)
}
