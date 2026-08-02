package com.reminder.server.domain.group

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

// 검증이 없으면 빈 그룹명이 그대로 저장되고, 두 번째부터 UNIQUE 위반으로 500 이 난다.
// spring-boot-starter-validation 은 의존성에 있었지만 어디에도 걸려 있지 않았다.
data class CreateGroupRequest(
    val userId: Long,
    @field:NotBlank(message = "그룹 이름은 비워둘 수 없습니다")
    val groupName: String,
    val password: String?,
    @field:Min(value = 1, message = "정원은 1명 이상이어야 합니다")
    val maxCount: Int = 5,
)

data class JoinGroupRequest(val userId: Long, val groupId: Long, val password: String?)

data class ChangePasswordRequest(
    val userId: Long,
    val groupId: Long,
    @field:NotBlank(message = "새 비밀번호는 비워둘 수 없습니다")
    val newPassword: String,
)

data class GroupResponse(val groupId: Long, val groupName: String, val memberCount: Int, val memberMaxCount: Int)
fun Group.toResponse() = GroupResponse(id, groupName, memberCounter, memberMaxCount)

@RestController
@RequestMapping("/group")
class GroupController(private val groupService: GroupService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(@Valid @RequestBody req: CreateGroupRequest): GroupResponse =
        groupService.createGroup(req.userId, req.groupName, req.password, req.maxCount).toResponse()

    @PostMapping("/member")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinGroup(@Valid @RequestBody req: JoinGroupRequest) =
        groupService.joinGroup(req.userId, req.groupId, req.password)

    @DeleteMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leaveGroup(@RequestParam userId: Long, @RequestParam groupId: Long) =
        groupService.leaveGroup(userId, groupId)

    @GetMapping("/info")
    fun getGroupInfo(@RequestParam userId: Long) =
        groupService.getGroupInfo(userId)

    @GetMapping("/search")
    fun searchGroups(@RequestParam groupName: String): List<GroupResponse> =
        groupService.searchGroups(groupName).map { it.toResponse() }

    @GetMapping("/check/name")
    fun checkGroupName(@RequestParam groupName: String): Map<String, Boolean> =
        mapOf("available" to groupService.isGroupNameAvailable(groupName))

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@Valid @RequestBody req: ChangePasswordRequest) =
        groupService.changePassword(req.userId, req.groupId, req.newPassword)
}
