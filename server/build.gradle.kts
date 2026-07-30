plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.4"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "com.reminder"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("com.mysql:mysql-connector-j")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// 테스트는 실제 MySQL·Redis 컨테이너에서 돈다.
	// H2/임베디드 Redis를 쓰면 검증 대상(InnoDB row lock, Redis Lua 원자성)이 사라진다.
	// Testcontainers 2.x 는 모듈명이 testcontainers-* 로 바뀌었다 (1.x: junit-jupiter, mysql)
	// 버전은 Spring Boot 4.0.4 BOM 이 관리한다 (현재 2.0.4)
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()

	// 테스트 컨테이너가 운영과 "같은" 스키마를 쓰도록 infra/init.sql 경로를 넘긴다.
	// 복사본을 두면 두 파일이 어긋나므로 원본을 그대로 참조한다.
	systemProperty(
		"schema.init.sql",
		project.rootDir.parentFile.resolve("infra/init.sql").absolutePath,
	)
}

// bootRun 실행 시 프로젝트 루트의 .env 파일을 자동으로 환경 변수로 주입
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	val envFile = project.rootDir.parentFile.resolve(".env")
	if (envFile.exists()) {
		envFile.readLines()
			.filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
			.forEach { line ->
				val (key, value) = line.split("=", limit = 2)
				environment(key.trim(), value.trim())
			}
	}
}
