package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.DuplicateGroupNameException
import com.reminder.server.global.exception.GroupPasswordMismatchException
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 데모 페이지를 눌러보다 나온 문제들을 고정한다.
 *
 *   - 맞는 비밀번호인데 앞뒤 공백 하나 때문에 계속 불일치가 났다
 *   - 같은 이름으로 그룹을 만들면 UNIQUE 위반이 그대로 올라와 500 이 나갔다
 */
class GroupValidationTest : IntegrationTest() {

    @Autowired lateinit var groupService: GroupService
    @Autowired lateinit var userRepository: UserRepository

    private fun newUser(tag: String) =
        userRepository.save(User("gh-$tag", "nick-$tag", "repo"))

    @Test
    @DisplayName("비밀번호는 생성·참여 양쪽에서 trim 되므로 앞뒤 공백이 있어도 참여된다")
    fun passwordIsTrimmedOnBothSides() {
        val owner = newUser("pw-owner")
        val group = groupService.createGroup(owner.id, "공백비번그룹", "  secret  ", 5)

        // 저장할 때 trim 했으니 참여할 때도 trim 해야 대칭이 맞는다
        assertThatCode { groupService.joinGroup(newUser("pw-a").id, group.id, "secret") }
            .doesNotThrowAnyException()
        assertThatCode { groupService.joinGroup(newUser("pw-b").id, group.id, "  secret  ") }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("틀린 비밀번호는 여전히 거부된다")
    fun wrongPasswordStillRejected() {
        val owner = newUser("pw-owner2")
        val group = groupService.createGroup(owner.id, "비번그룹2", "secret", 5)

        assertThatThrownBy { groupService.joinGroup(newUser("pw-c").id, group.id, "wrong") }
            .isInstanceOf(GroupPasswordMismatchException::class.java)
        assertThatThrownBy { groupService.joinGroup(newUser("pw-d").id, group.id, null) }
            .isInstanceOf(GroupPasswordMismatchException::class.java)
        // 공백만 넣은 것은 "비밀번호 없음"과 같게 취급한다
        assertThatThrownBy { groupService.joinGroup(newUser("pw-e").id, group.id, "   ") }
            .isInstanceOf(GroupPasswordMismatchException::class.java)
    }

    @Test
    @DisplayName("공백만 있는 비밀번호로 만든 그룹은 공개 그룹이 된다")
    fun blankPasswordMeansPublicGroup() {
        val owner = newUser("pw-owner3")
        val group = groupService.createGroup(owner.id, "공백만비번", "   ", 5)

        assertThat(group.groupPw).isNull()
        assertThatCode { groupService.joinGroup(newUser("pw-f").id, group.id, null) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("중복 그룹명은 DB UNIQUE 위반(500)이 아니라 명시적 예외로 막는다")
    fun duplicateGroupNameIsRejectedBeforeReachingDb() {
        groupService.createGroup(newUser("dup1").id, "중복이름", null, 5)

        assertThatThrownBy { groupService.createGroup(newUser("dup2").id, "중복이름", null, 5) }
            .isInstanceOf(DuplicateGroupNameException::class.java)
    }

    @Test
    @DisplayName("그룹명도 trim 되므로 앞뒤 공백만 다른 이름은 중복으로 걸린다")
    fun groupNameIsTrimmedSoWhitespaceVariantsCollide() {
        groupService.createGroup(newUser("trim1").id, "  이름앞뒤공백  ", null, 5)

        assertThat(groupService.isGroupNameAvailable("이름앞뒤공백")).isFalse()
        assertThatThrownBy { groupService.createGroup(newUser("trim2").id, "이름앞뒤공백", null, 5) }
            .isInstanceOf(DuplicateGroupNameException::class.java)
    }
}
