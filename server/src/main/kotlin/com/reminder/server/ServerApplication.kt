package com.reminder.server

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing       // BaseTimeEntity createdAt/updatedAt 자동 주입
// 제목·버전도 Flask 와 같게 둔다 (app/__init__.py 의 API_TITLE, API_VERSION).
// springdoc 은 이걸 프로퍼티로 받지 않아서 애노테이션으로 지정한다.
@OpenAPIDefinition(info = Info(title = "코테 독촉기", version = "v1"))
class ServerApplication

fun main(args: Array<String>) {
	runApplication<ServerApplication>(*args)
}
