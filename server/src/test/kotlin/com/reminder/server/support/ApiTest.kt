package com.reminder.server.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestTemplate

/**
 * 실제 HTTP 요청으로 엔드포인트를 호출하는 테스트의 베이스.
 *
 * [왜 필요했나]
 * 기존 테스트 35개는 전부 서비스를 직접 호출했다(groupService.joinGroup(...)).
 * DB·Redis는 실제로 띄웠지만 **웹 계층은 통째로 빠져 있었다.**
 * 그래서 이 계층에만 사는 것들이 전부 미검증으로 남았다:
 *
 *   요청 역직렬화 / @Valid 검증 / 예외→상태코드 매핑 / 응답 본문과 Content-Type
 *
 * 실제로 서버를 띄워 눌러보고 나서야 버그 여섯 개가 나왔다. 테스트가 통과한다는 것이
 * 소프트웨어가 동작한다는 뜻이 아니었고, 그 간극이 정확히 이 계층이었다.
 *
 * [규칙]
 * 이 파일을 상속한 테스트는 서비스나 리포지토리를 직접 호출하지 않는다.
 * 검증은 반드시 HTTP 응답(상태코드 + 본문)으로 한다.
 * 준비 데이터(fixture)를 만들 때만 API 를 연달아 호출한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiTest : IntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    // 4xx/5xx 에서 예외를 던지지 않도록 기본 에러 핸들러를 끈다.
    // 상태코드 자체가 검증 대상이기 때문이다.
    //
    // 요청 팩토리를 JDK HttpClient 로 바꾼다. 기본값(SimpleClientHttpRequestFactory)은
    // HttpURLConnection 을 쓰는데 PATCH 를 지원하지 않아 "Invalid HTTP method: PATCH" 로
    // 요청이 나가기도 전에 터진다. 아래 patch() 헬퍼는 원래부터 있었지만 이걸 호출하는
    // 테스트가 없어서 동작하지 않는다는 사실이 드러나지 않았다.
    private val rest: RestTemplate by lazy {
        RestTemplate(JdkClientHttpRequestFactory()).apply {
            errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
                override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            }
        }
    }

    protected fun url(path: String) = "http://localhost:$port$path"

    protected fun post(path: String, body: String): ResponseEntity<String> =
        exchange(HttpMethod.POST, path, body)

    protected fun patch(path: String, body: String): ResponseEntity<String> =
        exchange(HttpMethod.PATCH, path, body)

    protected fun get(path: String): ResponseEntity<String> =
        exchange(HttpMethod.GET, path, null)

    protected fun delete(path: String): ResponseEntity<String> =
        exchange(HttpMethod.DELETE, path, null)

    private fun exchange(method: HttpMethod, path: String, body: String?): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return rest.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
    }

    /** 응답 본문에서 숫자 필드 하나를 꺼낸다. 후속 요청에 쓸 id 확보용. */
    protected fun ResponseEntity<String>.longField(name: String): Long =
        Regex("\"$name\"\\s*:\\s*(\\d+)").find(body ?: "")
            ?.groupValues?.get(1)?.toLong()
            ?: error("응답에 $name 이 없습니다: $body")

    /** 테스트용 유저를 API 로 만들고 userId 를 돌려준다. */
    protected fun createUser(tag: String): Long =
        post("/users", """{"githubId":"$tag","nickname":"$tag","repositoryName":"repo-$tag"}""")
            .longField("userId")
}
