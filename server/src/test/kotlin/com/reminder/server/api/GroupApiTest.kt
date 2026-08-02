package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * 서버를 직접 띄워 눌러보다 나온 버그들을 HTTP 계층에서 고정한다.
 *
 * 이 중 넷은 서비스를 직접 호출하는 기존 테스트로는 잡을 수 없었다 —
 * @Valid 도, 예외→상태코드 매핑도, 응답 본문도 컨트롤러를 지나야 존재하기 때문이다.
 */
class GroupApiTest : ApiTest() {

    @Test
    @DisplayName("빈 그룹명은 500이 아니라 400과 사유를 돌려준다")
    fun blankGroupNameIsRejectedWith400() {
        val userId = createUser("blank-name")

        val res = post("/group", """{"userId":$userId,"groupName":"","password":null,"maxCount":5}""")

        // 검증 전에는 빈 이름이 그대로 저장되고, 두 번째부터 UNIQUE 위반으로 500 이 났다.
        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body).contains("groupName")
    }

    @Test
    @DisplayName("정원이 0 이하면 400")
    fun nonPositiveMaxCountIsRejected() {
        val userId = createUser("bad-max")

        val res = post("/group", """{"userId":$userId,"groupName":"정원0그룹","password":null,"maxCount":0}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    @DisplayName("중복 그룹명은 500이 아니라 409")
    fun duplicateGroupNameReturns409() {
        val first = createUser("dup-a")
        val second = createUser("dup-b")
        post("/group", """{"userId":$first,"groupName":"중복API","password":null,"maxCount":5}""")

        val res = post("/group", """{"userId":$second,"groupName":"중복API","password":null,"maxCount":5}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    @DisplayName("생성 응답의 memberCount 는 1이다 — 생성자 본인이 멤버이므로")
    fun createResponseShowsOwnerAsMember() {
        val userId = createUser("resp-owner")

        val res = post("/group", """{"userId":$userId,"groupName":"응답검증그룹","password":null,"maxCount":5}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(res.longField("memberCount")).isEqualTo(1)
    }

    @Test
    @DisplayName("생성 시 붙은 공백은 저장 전에 잘린다")
    fun passwordIsTrimmedWhenCreating() {
        val owner = createUser("ws-owner")
        val joiner = createUser("ws-joiner")
        val groupId = post("/group", """{"userId":$owner,"groupName":"공백비번생성","password":" pw123 ","maxCount":5}""")
            .longField("groupId")

        val res = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":"pw123"}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("참여 시 붙은 공백도 잘린다 — 복사·붙여넣기로 공백이 따라오는 경우")
    fun passwordIsTrimmedWhenJoining() {
        val owner = createUser("ws2-owner")
        val joiner = createUser("ws2-joiner")
        val groupId = post("/group", """{"userId":$owner,"groupName":"공백비번참여","password":"pw123","maxCount":5}""")
            .longField("groupId")

        // 위 테스트만 있으면 참여 쪽 trim 을 제거해도 통과한다(실제로 확인함).
        // 방향을 뒤집은 이 케이스가 있어야 양쪽 trim 이 모두 고정된다.
        val res = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":"  pw123  "}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("틀린 비밀번호는 400 이고 메시지가 담긴다")
    fun wrongPasswordReturns400WithMessage() {
        val owner = createUser("wrong-owner")
        val joiner = createUser("wrong-joiner")
        val groupId = post("/group", """{"userId":$owner,"groupName":"틀린비번API","password":"right","maxCount":5}""")
            .longField("groupId")

        val res = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":"nope"}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body).contains("비밀번호")
    }

    @Test
    @DisplayName("멤버가 아닌 유저의 탈퇴는 400")
    fun nonMemberLeaveReturns400() {
        val owner = createUser("leave-owner")
        val outsider = createUser("leave-outsider")
        val groupId = post("/group", """{"userId":$owner,"groupName":"비멤버탈퇴API","password":null,"maxCount":5}""")
            .longField("groupId")

        val res = delete("/group/leave?userId=$outsider&groupId=$groupId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    @DisplayName("마지막 멤버가 나가면 그룹이 사라져 이후 참여가 404")
    fun lastMemberLeavingDeletesGroup() {
        val owner = createUser("solo-api")
        val groupId = post("/group", """{"userId":$owner,"groupName":"혼자API","password":null,"maxCount":5}""")
            .longField("groupId")

        assertThat(delete("/group/leave?userId=$owner&groupId=$groupId").statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val other = createUser("solo-api-other")
        assertThat(post("/group/member", """{"userId":$other,"groupId":$groupId,"password":null}""").statusCode)
            .describedAs("그룹이 삭제됐으므로 참여 시도는 404 여야 한다")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("본문 없는 성공 응답은 201/204 이고 본문이 비어 있다")
    fun emptyBodySuccessResponses() {
        val owner = createUser("empty-owner")
        val joiner = createUser("empty-joiner")
        val groupId = post("/group", """{"userId":$owner,"groupName":"빈본문API","password":null,"maxCount":5}""")
            .longField("groupId")

        // 데모 페이지가 이 응답들에서 깨졌다 — res.json() 실패 후 res.text() 를 부르며
        // "body stream already read" 로 또 던져 화면 갱신이 통째로 건너뛰어졌다.
        val join = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":null}""")
        assertThat(join.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(join.body).isNull()

        val leave = delete("/group/leave?userId=$joiner&groupId=$groupId")
        assertThat(leave.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(leave.body).isNull()
    }

    @Test
    @DisplayName("다중 그룹 참여 시 조회는 배열로 전부 반환한다")
    fun groupInfoReturnsAllJoinedGroups() {
        val user = createUser("multi-api")
        val ownerA = createUser("multi-a")
        val ownerB = createUser("multi-b")
        val a = post("/group", """{"userId":$ownerA,"groupName":"다중A","password":null,"maxCount":5}""").longField("groupId")
        val b = post("/group", """{"userId":$ownerB,"groupName":"다중B","password":null,"maxCount":5}""").longField("groupId")
        post("/group/member", """{"userId":$user,"groupId":$a,"password":null}""")
        post("/group/member", """{"userId":$user,"groupId":$b,"password":null}""")

        val res = get("/group/info?userId=$user")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.body).startsWith("[").contains("다중A").contains("다중B")
    }
}
