/**
 * k6 부하 테스트 스크립트
 * ======================
 * 목적: MariaDB 커넥션 풀 고갈(Connection Pool Exhausted) 시나리오 재현 및
 *       인덱스 추가 / 풀 사이즈 튜닝 전후 성능 비교
 *
 * 사전 조건:
 *   1. seed_data.py 실행 완료 (더미 유저 100명, 커밋 ~50,000건)
 *   2. 서버 실행: uv run flask --app run run (또는 gunicorn)
 *   3. k6 설치: brew install k6
 *
 * 실행:
 *   k6 run k6_script.js                        # 기본 (장애 재현)
 *   k6 run -e SCENARIO=tuned k6_script.js       # 튜닝 후 비교
 *   k6 run -e SCENARIO=group k6_script.js       # 그룹 조회 시나리오
 *   k6 run --out json=result.json k6_script.js  # 결과 저장
 *
 * 측정 지표:
 *   - http_req_duration  : 응답 시간 (p95, p99 확인)
 *   - http_req_failed    : 실패율 (커넥션 고갈 시 503/500 급증)
 *   - http_reqs          : 초당 처리량 (RPS)
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

// ── 설정 ────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCENARIO = __ENV.SCENARIO || "exhaustion"; // exhaustion | tuned | group

// 더미 유저 ID 범위 (seed_data.py가 생성한 유저)
// 실제 DB에서 seed_user 유저들의 user_id 범위를 확인 후 수정하세요
const USER_ID_START = __ENV.USER_ID_START ? parseInt(__ENV.USER_ID_START) : 1;
const USER_ID_END = __ENV.USER_ID_END ? parseInt(__ENV.USER_ID_END) : 100;

// ── 커스텀 메트릭 ────────────────────────────────────────────
const errorRate = new Rate("error_rate");
const rankLatency = new Trend("rank_latency", true);
const groupLatency = new Trend("group_latency", true);

// ── 시나리오별 옵션 ──────────────────────────────────────────
const scenarios = {
  // [장애 재현] pool_size=5, max_overflow=10 기본값으로 VU 50 동시 접속
  // → Full Scan 쿼리가 커넥션을 오래 점유 → 풀 고갈 → 503 에러 발생
  exhaustion: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20 },  // 점진적 증가
      { duration: "20s", target: 50 },  // 임계점 도달 (pool 고갈 구간)
      { duration: "10s", target: 50 },  // 유지
      { duration: "10s", target: 0 },   // 감소
    ],
    gracefulRampDown: "5s",
  },

  // [튜닝 후 비교] SQLALCHEMY_POOL_SIZE=20, 인덱스 추가 후 동일 부하
  tuned: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "10s", target: 20 },
      { duration: "20s", target: 50 },
      { duration: "20s", target: 100 }, // 더 높은 부하까지 안정 확인
      { duration: "10s", target: 0 },
    ],
    gracefulRampDown: "5s",
  },

  // [그룹 조회] count_commits_for_current_month() window function 타겟
  group: {
    executor: "constant-vus",
    vus: 30,
    duration: "30s",
  },

  /**
   * [mixed] Spring Boot mixed 시나리오와 대비용
   *
   * Flask에는 Redis 캐시 계층이 없어 GET /rank가 항상 DB DENSE_RANK()를 실행함.
   * Spring Boot mixed는 Write+Read 동시 부하(VU 50, 1분)에서 p95 24ms를 기록했으나
   * Flask는 캐시 없는 pure Read 부하만으로 어떤 p95를 보이는지 측정.
   *
   * 비교 논점:
   *   "Spring Boot는 Write가 Redis를 갱신하는 동안에도 Read 경로(Redis HGET)가
   *    완전히 분리되어 있어 p95 24ms를 유지했다.
   *    Flask는 Write 없는 동일 조건(VU 50)의 Read에서 p95가 얼마인가?"
   */
  mixed: {
    executor: "constant-vus",
    vus: 50,   // Spring Boot mixed 동일 VU
    duration: "1m",
  },
};

export const options = {
  scenarios: {
    main: scenarios[SCENARIO] || scenarios.exhaustion,
  },
  thresholds: {
    error_rate: [{ threshold: "rate<0.05", abortOnFail: false }],
    http_req_duration: ["p(95)<2000"],
  },
};

// ── 헬퍼 ────────────────────────────────────────────────────
function randomUserId() {
  return Math.floor(Math.random() * (USER_ID_END - USER_ID_START + 1)) + USER_ID_START;
}

// ── 시나리오별 요청 함수 ─────────────────────────────────────

/** GET /rank/ — _get_top_30_commits_query() 호출 (window function + full scan) */
function testRankEndpoint() {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/rank`, {
    tags: { endpoint: "rank" },
  });
  rankLatency.add(Date.now() - start);

  const ok = check(res, {
    "rank: status 200": (r) => r.status === 200,
    "rank: has data": (r) => {
      try {
        const body = JSON.parse(r.body);
        return Array.isArray(body);
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!ok);

  if (res.status !== 200) {
    console.error(`[rank] status=${res.status} body=${res.body?.slice(0, 100)}`);
  }
}

/** GET /rank/users?user_id=N — 특정 유저 랭킹 조회 */
function testUserRankEndpoint() {
  const userId = randomUserId();
  const res = http.get(`${BASE_URL}/rank/users?user_id=${userId}`, {
    tags: { endpoint: "user_rank" },
  });

  const ok = check(res, {
    "user_rank: status 200 or 404": (r) => r.status === 200 || r.status === 404,
  });

  errorRate.add(res.status >= 500);
}

/** GET /group/info?user_id=N — count_commits_for_current_month() 호출 */
function testGroupEndpoint() {
  const userId = randomUserId();
  const start = Date.now();
  const res = http.get(`${BASE_URL}/group/info?user_id=${userId}`, {
    tags: { endpoint: "group" },
  });
  groupLatency.add(Date.now() - start);

  const ok = check(res, {
    "group: status 200 or 404": (r) => r.status === 200 || r.status === 404,
    "group: not 500": (r) => r.status < 500,
  });

  errorRate.add(res.status >= 500);
}

// ── 메인 실행 ────────────────────────────────────────────────
export default function () {
  if (SCENARIO === "group") {
    testGroupEndpoint();
    sleep(0.5);
  } else if (SCENARIO === "mixed") {
    // Flask mixed: 캐시 없는 순수 Read 부하
    // GET /rank → 매 요청마다 DENSE_RANK() DB Full Scan
    // Spring Boot mixed(Write+Read 동시, p95 24ms)와 대비
    if (Math.random() < 0.7) {
      testRankEndpoint();
    } else {
      testUserRankEndpoint();
    }
    sleep(0.2);
  } else {
    if (Math.random() < 0.7) {
      testRankEndpoint();
    } else {
      testUserRankEndpoint();
    }
    sleep(0.3);
  }
}

// ── 최종 요약 출력 ───────────────────────────────────────────
export function handleSummary(data) {
  const reqs = data.metrics.http_reqs?.values?.count ?? 0;
  const failed = data.metrics.http_req_failed?.values?.rate ?? 0;
  const p95 = data.metrics.http_req_duration?.values?.["p(95)"] ?? 0;
  const p99 = data.metrics.http_req_duration?.values?.["p(99)"] ?? data.metrics.http_req_duration?.values?.["max"] ?? 0;
  const rps = data.metrics.http_reqs?.values?.rate ?? 0;

  const summary = `
========================================
  부하 테스트 결과 (시나리오: ${SCENARIO})
========================================
  총 요청 수    : ${reqs.toLocaleString()} 건
  실패율        : ${(failed * 100).toFixed(2)} %
  RPS           : ${rps.toFixed(1)} req/s
  p95 응답시간  : ${p95.toFixed(0)} ms
  p99 응답시간  : ${p99.toFixed(0)} ms

  [판단 기준]
  실패율 > 5%   → 커넥션 풀 고갈 발생
  p95   > 2000ms → DB 쿼리 병목 발생
========================================
`;

  console.log(summary);

  return {
    stdout: summary,
    "k6_result.json": JSON.stringify(data, null, 2),
  };
}
