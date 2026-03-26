package com.reminder.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableJpaAuditing       // BaseTimeEntity createdAt/updatedAt 자동 주입
@EnableScheduling        // RankingSelfHealingScheduler 활성화
class ServerApplication

fun main(args: Array<String>) {
	runApplication<ServerApplication>(*args)
}
