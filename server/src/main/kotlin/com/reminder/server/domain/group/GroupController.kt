package com.reminder.server.domain.group

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class CreateGroupRequest(val userId: Long, val groupName: String, val password: String?, val maxCount: Int = 5)
data class JoinGroupRequest(val userId: Long, val groupId: Long, val password: String?)
data class ChangePasswordRequest(val userId: Long, val groupId: Long, val newPassword: String)

data class GroupResponse(val groupId: Long, val groupName: String, val memberCount: Int, val memberMaxCount: Int)
fun Group.toResponse() = GroupResponse(id, groupName, memberCounter, memberMaxCount)

@RestController
@RequestMapping("/group")
class GroupController(private val groupService: GroupService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(@RequestBody req: CreateGroupRequest): GroupResponse =
        groupService.createGroup(req.userId, req.groupName, req.password, req.maxCount).toResponse()

    @PostMapping("/member")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinGroup(@RequestBody req: JoinGroupRequest) =
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
    fun changePassword(@RequestBody req: ChangePasswordRequest) =
        groupService.changePassword(req.userId, req.groupId, req.newPassword)
}
