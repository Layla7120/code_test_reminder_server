package com.reminder.server.gate

import java.io.File

class GateResult(val exitCode: Int, val output: String)

/**
 * 저장소 루트의 셸 게이트를 실행한다.
 *
 * 테스트의 작업 디렉터리는 server/ 라 루트를 직접 못 잡는다. 상대 경로를 박아두는 대신
 * 스크립트가 실제로 있는 곳까지 올라가 찾는다 — 나중에 디렉터리가 한 겹 더 생겨도 안 깨진다.
 */
fun runGate(relativePath: String): GateResult {
    val script = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, relativePath) }
        .firstOrNull { it.isFile }
        ?: error("게이트 스크립트를 찾지 못했습니다: $relativePath")

    val process = ProcessBuilder("bash", script.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    return GateResult(process.waitFor(), output)
}
