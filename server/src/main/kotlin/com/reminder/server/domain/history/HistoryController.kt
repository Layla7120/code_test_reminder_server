package com.reminder.server.domain.history

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

// 검증이 없으면 빈 문제 번호와 음수 풀이 시간이 그대로 저장된다.
data class SaveHistoryRequest(
    val userId: Long,
    @field:NotBlank(message = "문제 번호는 비워둘 수 없습니다")
    val problemNum: String,
    @field:Positive(message = "풀이 시간은 1초 이상이어야 합니다")
    val solveTime: Int,
)

// historyId 는 내보내지 않는다 — 클라이언트가 행을 지목할 일이 없다(문제 번호가 키다).
data class HistoryResponse(val problemNum: String, val solveTime: Int, val solvedAt: LocalDateTime)

fun History.toResponse() = HistoryResponse(problemNum, solveTime, solvedAt)

@RestController
@RequestMapping("/history")
class HistoryController(private val historyService: HistoryService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun saveHistory(@Valid @RequestBody req: SaveHistoryRequest): HistoryResponse =
        historyService.saveHistory(req.userId, req.problemNum, req.solveTime).toResponse()

    @GetMapping
    fun getHistory(@RequestParam userId: Long): List<HistoryResponse> =
        historyService.getHistory(userId).map { it.toResponse() }
}
