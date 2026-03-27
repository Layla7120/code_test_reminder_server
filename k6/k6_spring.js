/**
 * k6 부하 테스트 — Spring Boot 4.0 (Flask 대비 성능 비교)
 * =========================================================
 * Flask 레거시와 동일한 시나리오/조건으로 실행하여 성능 차이를 수치로 증명
 *
 * [Flask 레거시 문제점]
 *   1. DENSE_RANK() window function → 매 요청마다 전체 테이블 Full Scan
 *   2. 인덱스 없음 → 쿼리 O(N)
 *   3. Connection Pool size=5 (기본값) → VU 50에서 고갈
 *   → 결과: p95 ≈ 86ms (tuned 이후), 고갈 시 503 다발
 *
 * [Spring Boot 개선 사항]
 *   1. Redis ZSET 캐시 → DENSE_RANK() DB 호출 제거, O(1) HGET
 *   2. HikariCP pool-size=20 (Flask tuned 조건과 동일)
 *   3. idx_commit_date, idx_commit_user 인덱스
 *   4. Graceful Degradation: Redis Miss → ZREVRANK O(log N) fallback
 *   → 목표: p95 ≪ 86ms, 실패율 ≈ 0%
 *
 * 사전 조건:
 *   1. seed_data.py 실행 (동일 더미 데이터)
 *   2. Spring Boot 서버 실행: JAVA_HOME=... ./gradlew bootRun
 *   3. Redis 실행: brew services start redis
 *
 * 실행:
 *   k6 run k6/k6_spring.js                           # 기본 (tuned 시나리오)
 *   k6 run -e SCENARIO=exhaustion k6/k6_spring.js    # 극한 부하
 *   k6 run -e SCENARIO=redis k6/k6_spring.js         # Redis 캐시 집중
 *   k6 run --out json=k6_result_spring.json k6/k6_spring.js
 *
 * Flask 비교 실행 (동일 터미널):
 *   k6 run --out json=k6_result_before.json k6/k6_flask.js
 *   k6 run --out json=k6_result_after.json  k6/k6_spring.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

// ── 설정 ─────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCENARIO = __ENV.SCENARIO || "tuned"; // tuned | exhaustion | redis | group

const USER_ID_START = __ENV.USER_ID_START ? parseInt(__ENV.USER_ID_START) : 1;
const USER_ID_END   = __ENV.USER_ID_END   ? parseInt(__ENV.USER_ID_END)   : 100;

// ── 커스텀 메트릭 ─────────────────────────────────────────────
const errorRate    = new Rate("error_rate");
const rankLatency  = new Trend("rank_latency",  true); // Redis HGET O(1) 경로
const groupLatency = new Trend("group_latency", true);
const userLatency  = new Trend("user_rank_latency", true); // Graceful Degradation 경로

// ── 시나리오별 옵션 ───────────────────────────────────────────
const scenarios = {
  /**
   * [기본] Flask tuned 조건과 동일 — HikariCP=20, VU 최대 100
   * Flask p95=86ms 기준으로 Spring Boot가 얼마나 빠른지 확인
   */
  tuned: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20  },
      { duration: "20s", target: 50  },
      { duration: "20s", target: 100 }, // Flask tuned 최대 부하 동일
      { duration: "10s", target: 0   },
    ],
    gracefulRampDown: "5s",
  },

  /**
   * [극한 부하] Redis 없으면 고갈되는지 vs Redis 있으면 버티는지
   * Flask exhaustion(VU=50 → 503 다발)과 비교
   */
  exhaustion: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20  },
      { duration: "20s", target: 50  }, // Flask 고갈 임계점
      { duration: "10s", target: 50  },
      { duration: "10s", target: 0   },
    ],
    gracefulRampDown: "5s",
  },

  /**
   * [Redis 캐시 집중] /rank 엔드포인트 전용
   * Redis HGET O(1) → p95 수 ms 목표
   */
  redis: {
    executor: "constant-vus",
    vus: 50,
    duration: "30s",
  },

  /**
   * [그룹 조회] count_commits_for_current_month() → DTO Projection 비교
   * Flask: window function Full Scan / Spring Boot: JPQL GROUP BY + index
   */
  group: {
    executor: "constant-vus",
    vus: 30,
    duration: "30s",
  },
};

export const options = {
  scenarios: {
    main: scenarios[SCENARIO] || scenarios.tuned,
  },
  thresholds: {
    // Spring Boot 목표: 실패율 0%, p95 < 30ms (Flask tuned 86ms 대비)
    error_rate:        [{ threshold: "rate<0.01", abortOnFail: false }],
    http_req_duration: ["p(95)<500"], // Redis 캐시로 충분히 달성 가능
    rank_latency:      ["p(95)<30"],  // Redis HGET 직접 경로
  },
};

// ── 헬퍼 ─────────────────────────────────────────────────────
function randomUserId() {
  return Math.floor(Math.random() * (USER_ID_END - USER_ID_START + 1)) + USER_ID_START;
}

// ── 테스트 함수 ───────────────────────────────────────────────

/**
 * GET /rank
 * Flask: DENSE_RANK() window function, Full Scan, DB 직접 조회
 * Spring: Redis ZSET ZREVRANGE → HGET O(1), Cache Miss 시 DB fallback
 */
function testRankEndpoint() {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/rank`, {
    tags: { endpoint: "rank" },
  });
  rankLatency.add(Date.now() - start);

  const ok = check(res, {
    "rank: status 200":  (r) => r.status === 200,
    "rank: has data":    (r) => {
      try { return Array.isArray(JSON.parse(r.body)); }
      catch { return false; }
    },
  });

  errorRate.add(!ok);

  if (res.status !== 200) {
    console.error(`[rank] status=${res.status} body=${res.body?.slice(0, 100)}`);
  }
}

/**
 * GET /rank/users?userId=N
 * Flask: /rank/users?user_id=N (파라미터명 변경됨)
 * Spring: Redis HGET(score→denseRank) O(1) → Graceful Degradation → ZREVRANK O(log N)
 */
function testUserRankEndpoint() {
  const userId = randomUserId();
  const start = Date.now();
  const res = http.get(`${BASE_URL}/rank/users?userId=${userId}`, {
    tags: { endpoint: "user_rank" },
  });
  userLatency.add(Date.now() - start);

  const ok = check(res, {
    "user_rank: status 200 or 404": (r) => r.status === 200 || r.status === 404,
    "user_rank: not 500":           (r) => r.status < 500,
  });

  errorRate.add(res.status >= 500);
}

/**
 * GET /group/info?userId=N
 * Flask: count_commits_for_current_month() → window function per member
 * Spring: JPQL GROUP BY + idx_commit_date 인덱스 + DTO Projection (entity 미로딩)
 */
function testGroupEndpoint() {
  const userId = randomUserId();
  const start = Date.now();
  const res = http.get(`${BASE_URL}/group/info?userId=${userId}`, {
    tags: { endpoint: "group" },
  });
  groupLatency.add(Date.now() - start);

  const ok = check(res, {
    "group: status 200 or 404": (r) => r.status === 200 || r.status === 404,
    "group: not 500":           (r) => r.status < 500,
  });

  errorRate.add(res.status >= 500);
}

// ── 메인 실행 ─────────────────────────────────────────────────
export default function () {
  if (SCENARIO === "group") {
    testGroupEndpoint();
    sleep(0.5);
  } else if (SCENARIO === "redis") {
    // Redis 캐시 집중: rank 100%
    testRankEndpoint();
    sleep(0.1);
  } else {
    // 랭킹 7 : 유저랭킹 3 (Flask 동일 비율)
    if (Math.random() < 0.7) {
      testRankEndpoint();
    } else {
      testUserRankEndpoint();
    }
    sleep(0.3);
  }
}

// ── 최종 요약 출력 ────────────────────────────────────────────
export function handleSummary(data) {
  const reqs   = data.metrics.http_reqs?.values?.count                       ?? 0;
  const failed = data.metrics.http_req_failed?.values?.rate                  ?? 0;
  const p95    = data.metrics.http_req_duration?.values?.["p(95)"]           ?? 0;
  const p99    = data.metrics.http_req_duration?.values?.["p(99)"]
              ?? data.metrics.http_req_duration?.values?.["max"]             ?? 0;
  const rps    = data.metrics.http_reqs?.values?.rate                        ?? 0;
  const rp95   = data.metrics.rank_latency?.values?.["p(95)"]                ?? "N/A";

  const flaskBaseline = {
    tuned:      { p95: 86,   rps: "~600",  errorRate: "<1%" },
    exhaustion: { p95: 2000, rps: "~150",  errorRate: ">30%" },
  };
  const baseline = flaskBaseline[SCENARIO] || flaskBaseline.tuned;

  const summary = `
========================================
  코테독촉기 부하 테스트 — Spring Boot 4.0
  시나리오: ${SCENARIO}
========================================
  총 요청 수      : ${reqs.toLocaleString()} 건
  실패율          : ${(failed * 100).toFixed(2)} %
  RPS             : ${rps.toFixed(1)} req/s
  p95 응답시간    : ${p95.toFixed(0)} ms
  p99 응답시간    : ${p99.toFixed(0)} ms
  rank_p95 (Redis): ${typeof rp95 === "number" ? rp95.toFixed(0) + " ms" : rp95}

  ── Flask 레거시 비교 (${SCENARIO} 기준) ──
  Flask p95       : ${baseline.p95} ms
  Spring p95      : ${p95.toFixed(0)} ms  → ${p95 < baseline.p95 ? "✓ 개선" : "✗ 미개선"}
  Flask 실패율    : ${baseline.errorRate}
  Spring 실패율   : ${(failed * 100).toFixed(2)}%

  [개선 포인트]
  • Redis ZSET: DENSE_RANK() DB 제거 → rank_p95 ${typeof rp95 === "number" ? rp95.toFixed(0) + "ms" : rp95}
  • HikariCP=20 + 인덱스: 커넥션 고갈 방지
  • Graceful Degradation: Redis 장애 시에도 DB fallback
========================================
`;

  console.log(summary);

  return {
    stdout: summary,
    "k6_result_spring.json": JSON.stringify(data, null, 2),
  };
}
