package com.reminder.server.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * 테스트가 무엇을 실제로 검증했는지 **측정**한다.
 *
 * [왜 필요했나]
 * "이 테스트가 소유권 검사를 검증하나?" 를 사람이나 모델이 읽어서 판단하면 매번 답이
 * 달라진다. 대신 테스트를 돌려서 실제로 무슨 일이 일어났는지 기록하면 답이 하나로
 * 고정된다. 판단이 아니라 측정이다.
 *
 * 네 가지를 기록한다:
 *   1. 등록된 엔드포인트 전체       (라우팅 테이블)
 *   2. 테스트가 실제로 라우팅한 핸들러
 *   3. 핸들러별로 관측된 응답 상태 코드
 *   4. 핸들러별로 실제 발생한 예외 타입
 *   5. 테스트별로 생성된 사용자 수  (다중 사용자 검증 여부의 1차 지표)
 *
 * 3·4번이 핵심이다. "NotGroupOwnerException 이 검증되는가" 는
 * "어떤 테스트 실행 중 그 예외가 실제로 발생했는가" 와 같은 질문이고, 후자는 측정된다.
 *
 * [제약]
 * JVM 정적 상태다. Gradle 이 test 태스크를 단일 JVM 으로 돌리기 때문에 성립한다
 * (기본값 forkEvery = 0). forkEvery 를 켜면 집계가 쪼개져 리포트가 무의미해진다.
 */
object EndpointAudit {

    val all: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val hit: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "GroupController#changePassword -> 400" */
    val statuses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "GroupController#changePassword -> NotGroupOwnerException" */
    val exceptions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "GroupApiTest#groupInfoIsolatedBetweenUsers" -> 2 */
    val usersPerTest: MutableMap<String, Int> = ConcurrentHashMap()

    fun signature(h: HandlerMethod) = "${h.beanType.simpleName}#${h.method.name}"

    fun report(): String = buildString {
        appendLine("# 엔드포인트 계측 리포트")
        appendLine("# 총 ${all.size} / 라우팅됨 ${hit.size} / 미도달 ${(all - hit).size}")
        appendLine()
        (all - hit).sorted().forEach { appendLine("UNREACHED $it") }
        statuses.sorted().forEach { appendLine("STATUS    $it") }
        exceptions.sorted().forEach { appendLine("EXCEPTION $it") }
        usersPerTest.toSortedMap().forEach { (test, n) -> appendLine("USERS     $test = $n") }
    }

    fun writeReport() {
        if (all.isEmpty()) return
        File("build/endpoint-audit.txt").apply {
            parentFile.mkdirs()
            writeText(report())
        }
    }
}

/** 매칭된 핸들러와 응답 상태 코드를 기록한다. 404 는 핸들러가 없으므로 자동으로 빠진다. */
class HandlerRecordingInterceptor : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler is HandlerMethod) EndpointAudit.hit += EndpointAudit.signature(handler)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        if (handler is HandlerMethod) {
            EndpointAudit.statuses += "${EndpointAudit.signature(handler)} -> ${response.status}"
        }
    }
}

/**
 * 발생한 예외 타입을 기록하고 **처리하지 않는다**(null 반환).
 * 실제 처리는 GlobalExceptionHandler 가 그대로 한다 — 이 리졸버는 관찰만 한다.
 */
class ExceptionRecordingResolver : HandlerExceptionResolver, Ordered {

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (handler is HandlerMethod) {
            EndpointAudit.exceptions += "${EndpointAudit.signature(handler)} -> ${ex::class.simpleName}"
        }
        return null
    }
}

/** 테스트 하나가 끝날 때 생성된 사용자 수를 센다. 정리(clearStores) 전에 읽어야 한다. */
class UserCountRecorder : AfterTestExecutionCallback {

    override fun afterTestExecution(context: ExtensionContext) {
        val jdbc = SpringExtension.getApplicationContext(context).getBean(JdbcTemplate::class.java)
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Int::class.java) ?: 0
        val name = "${context.requiredTestClass.simpleName}#${context.requiredTestMethod.name}"
        EndpointAudit.usersPerTest[name] = count
    }
}

@TestConfiguration
class EndpointAuditConfig : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(HandlerRecordingInterceptor())
    }

    override fun extendHandlerExceptionResolvers(resolvers: MutableList<HandlerExceptionResolver>) {
        resolvers.add(0, ExceptionRecordingResolver())
    }

    // 매핑 빈이 완성된 뒤에 라우팅 테이블을 읽는다. 타입으로 주입하면 순환 참조가 난다.
    // 컨텍스트가 여러 번 떠도 Set 이라 중복이 쌓이지 않는다.
    @EventListener(ContextRefreshedEvent::class)
    fun captureAllEndpoints(event: ContextRefreshedEvent) {
        val mapping = event.applicationContext
            .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
        mapping.handlerMethods.values
            // Spring 이 등록하는 기본 에러 핸들러는 감사 대상이 아니다.
            // 빼지 않으면 항상 미도달로 잡혀 리포트에 상시 노이즈가 남는다.
            .filterNot { it.beanType.simpleName == "BasicErrorController" }
            .forEach { EndpointAudit.all += EndpointAudit.signature(it) }

        // 테스트 JVM 이 끝날 때 리포트를 남긴다. 한 번만 등록한다.
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread { EndpointAudit.writeReport() })
        }
    }

    companion object {
        private val shutdownHookRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
