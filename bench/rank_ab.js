// 랭킹 A/B 측정 — Redis 오프로딩 on/off
//
// 바꾸는 변수는 딱 하나: 서버의 ranking.redis.enabled
// 나머지(코드, DB, 시드 데이터, VU 수, 지속 시간, 엔드포인트 구성)는 전부 고정한다.
//
// 재는 것:
//   GET /rank                    전체 Top 30       (Redis: ZREVRANGE / DB: DENSE_RANK 전체 정렬)
//   GET /rank/users?userId=N     특정 유저 1명 순위  (Redis: ZSCORE+HGET / DB: DENSE_RANK 서브쿼리)
//
// 실행:
//   k6 run -e USERS=10000 -e ARM=redis bench/rank_ab.js
//
// 절대값보다 "유저 수가 늘 때 기울기가 어떻게 달라지는가"를 본다.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = parseInt(__ENV.USERS || '10000', 10);

const top30Latency = new Trend('top30_latency', true);
const userRankLatency = new Trend('user_rank_latency', true);

export const options = {
  scenarios: {
    rank_ab: {
      executor: 'constant-vus',
      vus: 30,
      duration: '60s',
      gracefulStop: '5s',
    },
  },
  // 임계값을 두지 않는다. 이 스크립트의 목적은 합격/불합격 판정이 아니라
  // 두 팔의 지연 분포를 같은 조건에서 나란히 얻는 것이다.
  thresholds: {},
};

export default function () {
  // 70% Top30 / 30% 개인 순위 — 실제 화면 진입 비율에 맞춘 구성
  if (Math.random() < 0.7) {
    const res = http.get(`${BASE}/rank`, { tags: { endpoint: 'top30' } });
    top30Latency.add(res.timings.duration);
    check(res, { 'top30 200': (r) => r.status === 200 });
  } else {
    // 매번 다른 유저를 조회해야 캐시 편향이 생기지 않는다
    const userId = Math.floor(Math.random() * USERS) + 1;
    const res = http.get(`${BASE}/rank/users?userId=${userId}`, {
      tags: { endpoint: 'user_rank' },
    });
    userRankLatency.add(res.timings.duration);
    check(res, { 'user_rank 200': (r) => r.status === 200 });
  }
}
