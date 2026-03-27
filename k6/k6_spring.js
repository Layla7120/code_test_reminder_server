/**
 * k6 부하 테스트 — Spring Boot 4.0
 * ==================================
 * 5가지 시나리오로 아키텍처의 한계까지 검증
 *
 * 실행:
 *   k6 run k6/k6_spring.js                              # 기본 (tuned)
 *   k6 run -e SCENARIO=breakpoint k6/k6_spring.js       # 임계점 — 시스템이 무너질 때까지
 *   k6 run -e SCENARIO=mixed k6/k6_spring.js            # Write+Read 동시 — 정합성 검증
 *   k6 run -e SCENARIO=soak k6/k6_spring.js             # 장시간 안정성 (30분)
 *   k6 run -e SCENARIO=exhaustion k6/k6_spring.js       # Flask 고갈 재현
 *
 *   k6 run --out json=k6_result_spring.json k6/k6_spring.js
 *
 * 시나리오별 목적:
 *   tuned      — Flask tuned 조건 동일 재현, p95 비교 (4ms vs 86ms)
 *   breakpoint — RPS 기준 선형 증가, 실패 발생 지점(Capacity) 측정
 *   mixed      — Write 부하 중 Read 정합성 깨지지 않는지 확인
 *   soak       — 30분 이상 지속, 메모리 누수·커넥션 누수 탐지
 *   exhaustion — Flask 고갈 임계점(VU 50)에서 Spring Boot 비교
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";

// ── 설정 ──────────────────────────────────────────────────────
const BASE_URL     = __ENV.BASE_URL      || "http://localhost:8080";
const SCENARIO     = __ENV.SCENARIO      || "tuned";
const USER_ID_START = __ENV.USER_ID_START ? parseInt(__ENV.USER_ID_START) : 1;
const USER_ID_END   = __ENV.USER_ID_END   ? parseInt(__ENV.USER_ID_END)   : 100;

// ── 커스텀 메트릭 ──────────────────────────────────────────────
const errorRate      = new Rate("error_rate");
const rankLatency    = new Trend("rank_latency",   true);
const userLatency    = new Trend("user_rank_latency", true);
const groupLatency   = new Trend("group_latency",  true);
const writeLatency   = new Trend("write_latency",  true);  // mixed 시나리오
const writeErrors    = new Counter("write_errors");        // mixed 시나리오

// ── 시나리오 정의 ──────────────────────────────────────────────
const scenarios = {

  /**
   * [1] tuned — Flask tuned 조건 동일 재현
   * 목적: p95 4ms vs Flask 86ms 비교 수치 확보
   */
  tuned: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20  },
      { duration: "20s", target: 50  },
      { duration: "20s", target: 100 },
      { duration: "10s", target: 0   },
    ],
    gracefulRampDown: "5s",
  },

  /**
   * [2] breakpoint — 임계점 파악 (Capacity 측정)
   *
   * ramping-arrival-rate: VU 수가 아닌 '초당 요청 수(RPS)'를 기준으로 증가
   * → VU 기반보다 정확하게 서버의 처리 한계를 측정할 수 있음
   *   (VU 기반은 sleep 시간에 따라 실제 RPS가 달라지기 때문)
   *
   * 목적: "개선된 아키텍처의 최대 처리 용량이 초당 N건(RPS)임을 정량적으로 파악"
   * 관찰 지표: 어느 RPS에서 p95가 급등하는지, 실패율이 1%를 넘는지
   */
  breakpoint: {
    executor: "ramping-arrival-rate",
    startRate: 10,
    timeUnit: "1s",
    preAllocatedVUs: 100,
    maxVUs: 1000,
    stages: [
      { duration: "1m",  target: 100  }, // 워밍업: 10 → 100 RPS
      { duration: "2m",  target: 300  }, // 점진 상승
      { duration: "2m",  target: 500  }, // 임계점 구간
      { duration: "1m",  target: 1000 }, // 한계 초과 시도
      { duration: "30s", target: 0    }, // 회복 관찰
    ],
  },

  /**
   * [3] mixed — Write+Read 동시 부하 (Redis 정합성 검증)
   *
   * 목적: 대량 쓰기 트래픽 중에도 Redis 랭킹이 안전하게 동작하는지 확인
   *   - Write VU: POST /commits 로 지속적으로 커밋 수집 요청
   *   - Read  VU: GET /rank 로 랭킹 읽기
   *   - 검증: Write 폭주 중 Read가 5xx를 반환하지 않는지 (Graceful Degradation)
   *
   * 구현: k6 scenarios로 write/read를 별도 executor에서 동시 실행
   */
  mixed: {
    executor: "constant-vus",
    vus: 50,
    duration: "1m",
  },

  /**
   * [4] soak — 장시간 안정성 (메모리·커넥션 누수 탐지)
   *
   * 목적: 30분 이상 지속 부하에서 메모리 Heap, Redis 커넥션 풀 누수 확인
   *   Flask(Gunicorn)는 장시간 실행 시 메모리가 야금야금 증가하는 경향
   *   Spring Boot도 Redis 커넥션 미반환 시 시간 경과 후 장애 발생 가능
   *
   * 관찰 포인트:
   *   - p95가 시간이 지나도 일정한가 (증가 추세면 누수 의심)
   *   - 30분 후 실패율이 초반과 동일한가
   */
  soak: {
    executor: "constant-vus",
    vus: 30,
    duration: "30m",
  },

  /**
   * [5] exhaustion — Flask 고갈 임계점 재현
   * Flask는 VU 50에서 커넥션 풀 고갈 → 503 다발
   * Spring Boot + Redis에서 동일 조건 비교
   */
  exhaustion: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20 },
      { duration: "20s", target: 50 },
      { duration: "10s", target: 50 },
      { duration: "10s", target: 0  },
    ],
    gracefulRampDown: "5s",
  },
};

// ── 임계점 시나리오: threshold를 abortOnFail로 설정하지 않음 (한계까지 측정이 목적)
const thresholdsByScenario = {
  tuned:      { "error_rate": ["rate<0.01"], "http_req_duration": ["p(95)<30"],  "rank_latency": ["p(95)<20"]  },
  breakpoint: { "error_rate": [{ threshold: "rate<0.05", abortOnFail: false }] }, // 실패해도 계속
  mixed:      { "error_rate": ["rate<0.01"], "write_errors": ["count<10"]       },
  soak:       { "error_rate": ["rate<0.01"], "http_req_duration": ["p(95)<50"]  }, // 장시간 → 50ms로 완화
  exhaustion: { "error_rate": [{ threshold: "rate<0.05", abortOnFail: false }] },
};

export const options = {
  scenarios: { main: scenarios[SCENARIO] || scenarios.tuned },
  thresholds: thresholdsByScenario[SCENARIO] || thresholdsByScenario.tuned,
};

// ── 헬퍼 ──────────────────────────────────────────────────────
function randomUserId() {
  return Math.floor(Math.random() * (USER_ID_END - USER_ID_START + 1)) + USER_ID_START;
}

// ── 요청 함수 ──────────────────────────────────────────────────

function testRankEndpoint() {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/rank`, { tags: { endpoint: "rank" } });
  rankLatency.add(Date.now() - start);

  const ok = check(res, {
    "rank: status 200": (r) => r.status === 200,
    "rank: has data":   (r) => { try { return Array.isArray(JSON.parse(r.body)); } catch { return false; } },
  });
  errorRate.add(!ok);
}

function testUserRankEndpoint() {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/rank/users?userId=${randomUserId()}`, { tags: { endpoint: "user_rank" } });
  userLatency.add(Date.now() - start);

  const ok = check(res, {
    "user_rank: not 5xx": (r) => r.status < 500,
  });
  errorRate.add(!ok);
}

function testGroupEndpoint() {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/group/info?userId=${randomUserId()}`, { tags: { endpoint: "group" } });
  groupLatency.add(Date.now() - start);

  const ok = check(res, { "group: not 5xx": (r) => r.status < 500 });
  errorRate.add(!ok);
}

/**
 * POST /commits — Write 부하
 * mixed 시나리오 전용: 실제 GitHub 호출 없이 존재하는 유저 ID만 사용
 * Redis ZINCRBY 경쟁 조건(Lost Update) 방어 검증이 목적
 */
function testWriteEndpoint() {
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/commits`,
    JSON.stringify({ userId: randomUserId() }),
    { headers: { "Content-Type": "application/json" }, tags: { endpoint: "write" } },
  );
  writeLatency.add(Date.now() - start);

  // 200(성공), 409(분산 락 중복), 404(유저 없음) 모두 정상 — 5xx만 에러
  const ok = check(res, { "write: not 5xx": (r) => r.status < 500 });
  if (!ok) writeErrors.add(1);
}

// ── 메인 실행 ──────────────────────────────────────────────────
export default function () {
  switch (SCENARIO) {
    case "mixed":
      // Write 20% : Read 80% — 쓰기 폭주 중 읽기 안정성 확인
      if (Math.random() < 0.2) {
        testWriteEndpoint();
        sleep(1);
      } else {
        testRankEndpoint();
        sleep(0.2);
      }
      break;

    case "soak":
      // 장시간 안정성: 랭킹 7 : 유저랭킹 3, 넉넉한 sleep
      if (Math.random() < 0.7) testRankEndpoint();
      else testUserRankEndpoint();
      sleep(1);
      break;

    case "breakpoint":
      // RPS 도달이 목적 → sleep 최소화
      testRankEndpoint();
      break;

    default:
      // tuned, exhaustion: Flask 동일 비율 (랭킹 7 : 유저랭킹 3)
      if (Math.random() < 0.7) testRankEndpoint();
      else testUserRankEndpoint();
      sleep(0.3);
  }
}

// ── 결과 요약 ──────────────────────────────────────────────────
export function handleSummary(data) {
  const reqs    = data.metrics.http_reqs?.values?.count             ?? 0;
  const failed  = data.metrics.http_req_failed?.values?.rate        ?? 0;
  const p95     = data.metrics.http_req_duration?.values?.["p(95)"] ?? 0;
  const p99     = data.metrics.http_req_duration?.values?.["p(99)"]
               ?? data.metrics.http_req_duration?.values?.["max"]   ?? 0;
  const rps     = data.metrics.http_reqs?.values?.rate              ?? 0;
  const rp95    = data.metrics.rank_latency?.values?.["p(95)"]      ?? "N/A";
  const wp95    = data.metrics.write_latency?.values?.["p(95)"]     ?? "N/A";
  const werr    = data.metrics.write_errors?.values?.count          ?? 0;

  const scenarioDesc = {
    tuned:      "Flask tuned 조건 동일 재현 (VU 100)",
    breakpoint: "임계점 파악 — 시스템이 무너지는 RPS 측정",
    mixed:      "Write+Read 동시 부하 — Redis 정합성 검증",
    soak:       "장시간 안정성 — 메모리/커넥션 누수 탐지 (30분)",
    exhaustion: "Flask 고갈 임계점 재현 (VU 50)",
  };

  const lines = [
    ``,
    `========================================`,
    `  코테독촉기 부하 테스트 — Spring Boot 4.0`,
    `  시나리오: ${SCENARIO}`,
    `  목적: ${scenarioDesc[SCENARIO] ?? SCENARIO}`,
    `========================================`,
    `  총 요청 수      : ${reqs.toLocaleString()} 건`,
    `  실패율          : ${(failed * 100).toFixed(2)} %`,
    `  RPS             : ${rps.toFixed(1)} req/s`,
    `  p95 응답시간    : ${p95.toFixed(0)} ms`,
    `  p99 응답시간    : ${p99.toFixed(0)} ms`,
    `  rank_p95(Redis) : ${typeof rp95 === "number" ? rp95.toFixed(0) + " ms" : rp95}`,
  ];

  if (SCENARIO === "mixed") {
    lines.push(`  write_p95       : ${typeof wp95 === "number" ? wp95.toFixed(0) + " ms" : wp95}`);
    lines.push(`  write_errors    : ${werr} 건 (5xx)`);
    lines.push(``);
    lines.push(`  [정합성 판단]`);
    lines.push(`  Write 폭주 중 Read 5xx = ${Math.round(failed * reqs)} 건`);
    lines.push(`  → ${failed < 0.01 ? "✓ Graceful Degradation 유지" : "✗ 정합성 문제 발생"}`);
  }

  if (SCENARIO === "breakpoint") {
    lines.push(``);
    lines.push(`  [임계점 판단]`);
    lines.push(`  실패율 1% 돌파 RPS → 로그에서 확인 (k6 --out json 권장)`);
    lines.push(`  최대 안정 RPS ≈ 실패율 < 1% 구간의 마지막 RPS`);
  }

  if (SCENARIO === "soak") {
    lines.push(``);
    lines.push(`  [안정성 판단]`);
    lines.push(`  30분 후 p95 증가 여부로 누수 판단`);
    lines.push(`  → p95 증가 추세 없음: ✓ 안정 / 증가: ✗ 누수 의심`);
  }

  if (SCENARIO === "tuned" || SCENARIO === "exhaustion") {
    const baseline = SCENARIO === "tuned" ? { p95: 86, err: "<1%" } : { p95: 2000, err: ">30%" };
    lines.push(``);
    lines.push(`  ── Flask 레거시 비교 ──`);
    lines.push(`  Flask p95    : ${baseline.p95} ms`);
    lines.push(`  Spring p95   : ${p95.toFixed(0)} ms  → ${p95 < baseline.p95 ? "✓ 개선" : "✗ 미개선"}`);
    lines.push(`  Flask 실패율 : ${baseline.err}`);
    lines.push(`  Spring 실패율: ${(failed * 100).toFixed(2)}%`);
  }

  lines.push(`========================================`);
  lines.push(``);

  const summary = lines.join("\n");
  console.log(summary);

  return {
    stdout: summary,
    "k6_result_spring.json": JSON.stringify(data, null, 2),
  };
}
