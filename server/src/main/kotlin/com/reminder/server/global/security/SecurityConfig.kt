package com.reminder.server.global.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource

@Configuration
class SecurityConfig {
    // 이 프로젝트는 인증/인가 없음 (레거시 Flask와 동일)
    // BCryptPasswordEncoder 사용을 위해 spring-security 의존성만 추가한 상태
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        // iOS 앱, 웹 데모 페이지, 로컬 개발 모두 허용
        // CorsConfigurationSource는 함수형 인터페이스 → SAM 변환으로 람다 사용
        return CorsConfigurationSource {
            CorsConfiguration().apply {
                allowedOriginPatterns = listOf("*")
                allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = false
                applyPermitDefaultValues()
            }
        }
    }
}
