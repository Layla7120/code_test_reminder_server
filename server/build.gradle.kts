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

/**
 * 모든 엔드포인트가 전송 계층(HTTP) 테스트를 갖는지 검사한다.
 *
 * 판정 근거는 EndpointAudit 계측이 남기는 build/endpoint-audit.txt 다.
 * 읽고 판단한 게 아니라 테스트 실행 중 실제로 라우팅된 핸들러를 측정한 결과다.
 *
 * 테스트가 통과해도 새 엔드포인트에 HTTP 테스트가 없으면 여기서 빌드가 깨진다.
 * 한때 테스트 35개가 전부 초록불인데 버그가 여섯 개였고, 원인은 전부가 서비스를
 * 직접 호출해 웹 계층을 건너뛴 것이었다 — 그 구조가 다시 생기지 않게 고정한다.
 */
val verifyEndpointCoverage by tasks.registering {
	description = "엔드포인트마다 HTTP 테스트가 있는지 검사한다"
	group = "verification"

	doLast {
		val report = layout.buildDirectory.file("endpoint-audit.txt").get().asFile
		if (!report.exists()) {
			// 테스트가 필터링 실행됐거나(--tests) 통합 테스트가 하나도 안 돌았다.
			// 부분 실행을 실패로 만들면 개발 중에 방해만 된다.
			logger.lifecycle("엔드포인트 리포트 없음 — 검사를 건너뛴다.")
			return@doLast
		}

		// 식별자 자체가 '#' 를 포함한다(Controller#method). 그래서 '#' 를 인라인 주석
		// 구분자로 쓰면 안 된다 — substringBefore('#') 는 이름을 통째로 잘라먹는다.
		// 줄 전체가 주석인 경우만 걸러내고, 식별자는 첫 공백까지로 끊는다.
		val allowFile = file("src/test/resources/endpoint-allowlist.txt")
		val allowed = if (allowFile.exists()) {
			allowFile.readLines()
				.map { it.trim() }
				.filterNot { it.isEmpty() || it.startsWith("#") }
				.map { it.split(Regex("\\s+")).first() }
				.toSet()
		} else {
			emptySet()
		}

		val unreached = report.readLines()
			.filter { it.startsWith("UNREACHED") }
			.map { it.removePrefix("UNREACHED").trim() }

		// 라우팅된 엔드포인트가 하나도 없으면 계측이 안 붙은 것이다. 판정하지 않는다.
		if (report.readLines().none { it.startsWith("STATUS") }) {
			logger.lifecycle("계측 기록이 비어 있음 — 검사를 건너뛴다.")
			return@doLast
		}

		val missing = unreached.filterNot { it in allowed }
		// 테스트가 생겼는데 허용 목록에 남아 있는 항목 — 지우라고 알린다.
		(allowed - unreached.toSet()).sorted().forEach {
			logger.warn("허용 목록에서 지울 것(이제 테스트가 있음): $it")
		}

		if (missing.isNotEmpty()) {
			throw GradleException(
				buildString {
					appendLine("전송 계층 테스트가 없는 엔드포인트 ${missing.size}개:")
					missing.sorted().forEach { appendLine("  $it") }
					appendLine()
					appendLine("ApiTest 를 상속한 테스트를 추가하거나,")
					appendLine("사유와 함께 src/test/resources/endpoint-allowlist.txt 에 등록한다.")
				},
			)
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()

	// 테스트 컨테이너가 운영과 "같은" 스키마를 쓰도록 infra/init.sql 경로를 넘긴다.
	// 복사본을 두면 두 파일이 어긋나므로 원본을 그대로 참조한다.
	systemProperty(
		"schema.init.sql",
		project.rootDir.parentFile.resolve("infra/init.sql").absolutePath,
	)

	finalizedBy(verifyEndpointCoverage)
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
