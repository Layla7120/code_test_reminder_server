package com.reminder.server.global.exception

class UserNotFoundException(id: Long) : RuntimeException("유저를 찾을 수 없습니다: $id")
class GroupNotFoundException(id: Long) : RuntimeException("그룹을 찾을 수 없습니다: $id")
class GroupFullException : RuntimeException("그룹 정원이 가득 찼습니다")
class GroupPasswordMismatchException : RuntimeException("그룹 비밀번호가 일치하지 않습니다")
class AlreadyInGroupException : RuntimeException("이미 해당 그룹에 참여 중입니다")
class NotGroupMemberException : RuntimeException("해당 그룹의 멤버가 아닙니다")
class NotGroupOwnerException : RuntimeException("그룹 소유자만 수행할 수 있습니다")
class CommitFetchAlreadyInProgressException : RuntimeException("이미 커밋 동기화가 진행 중입니다")
class GithubApiException(message: String) : RuntimeException(message)
