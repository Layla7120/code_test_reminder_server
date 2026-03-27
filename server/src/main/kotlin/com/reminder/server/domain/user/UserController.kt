package com.reminder.server.domain.user

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class LoginRequest(val githubId: String, val nickname: String, val repositoryName: String)
data class UpdateUserRequest(val userId: Long, val nickname: String?, val repositoryName: String?)
data class UserResponse(
    val userId: Long,
    val githubId: String,
    val nickname: String,
    val repositoryName: String,
    val active: Boolean,
    val createdAt: LocalDateTime,
)

fun User.toResponse() = UserResponse(id, githubId, nickname, repositoryName, active, createdAt)

@RestController
@RequestMapping("/users")
class UserController(private val userService: UserService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun loginOrCreate(@RequestBody req: LoginRequest): UserResponse =
        userService.loginOrCreate(req.githubId, req.nickname, req.repositoryName).toResponse()

    @GetMapping
    fun getUser(@RequestParam userId: Long): UserResponse =
        userService.getUser(userId).toResponse()

    @PatchMapping("/update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateUser(@RequestBody req: UpdateUserRequest) =
        userService.updateUser(req.userId, req.nickname, req.repositoryName)

    @DeleteMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(@RequestParam userId: Long) =
        userService.deleteUser(userId)

    @GetMapping("/nick_name")
    fun checkNickname(@RequestParam nickName: String): Map<String, Boolean> =
        mapOf("available" to userService.isNicknameAvailable(nickName))
}
