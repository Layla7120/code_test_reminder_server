package com.reminder.server.global.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
        NotGroupOwnerException::class,
        CommitFetchAlreadyInProgressException::class,
        IllegalArgumentException::class,
    )
    fun handleBadRequest(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "Bad request"))

    @ExceptionHandler(GithubApiException::class)
    fun handleGithubApi(e: GithubApiException) =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse(e.message ?: "GitHub API error"))
}
