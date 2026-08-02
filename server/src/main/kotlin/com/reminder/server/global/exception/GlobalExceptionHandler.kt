package com.reminder.server.global.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class, GroupNotFoundException::class)
    fun handleNotFound(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "Not found"))

    @ExceptionHandler(
        GroupFullException::class,
        GroupPasswordMismatchException::class,
        AlreadyInGroupException::class,
        NotGroupMemberException::class,
        NotGroupOwnerException::class,
        CommitFetchAlreadyInProgressException::class,
        IllegalArgumentException::class,
    )
    fun handleBadRequest(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "Bad request"))

    // @Valid 위반 — 첫 번째 필드 오류를 그대로 돌려준다.
    // 이게 없으면 Spring 기본 응답({timestamp,status,error,path})이 나가서
    // 무엇이 잘못됐는지 클라이언트가 알 수 없다.
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.firstOrNull()
            ?.let { "${it.field}: ${it.defaultMessage}" }
            ?: "요청 값이 올바르지 않습니다"
        return ResponseEntity.badRequest().body(ErrorResponse(message))
    }

    @ExceptionHandler(DuplicateGroupNameException::class)
    fun handleConflict(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Conflict"))

    // UNIQUE 제약 위반 등이 여기까지 오면 500 이 나간다. 이건 클라이언트 잘못이므로 409 가 맞다.
    // 서비스에서 미리 걸러내는 게 우선이고, 이 핸들러는 경합으로 빠져나간 경우의 마지막 방어선이다.
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: DataIntegrityViolationException) =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("이미 존재하는 값이거나 제약 조건에 맞지 않습니다"))

    @ExceptionHandler(GithubApiException::class)
    fun handleGithubApi(e: GithubApiException) =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(e.message ?: "GitHub API error"))
}
