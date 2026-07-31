# 랭킹 A/B 측정

## 무엇을 재는가

**바꾸는 변수는 하나뿐이다: `ranking.redis.enabled`**

| 팔 | 랭킹 조회 경로 |
|---|---|
| `redis` | Redis ZSET (`ZREVRANGE`) + score→denseRank HASH (`HGET`) |
| `db` | MySQL `DENSE_RANK()` 윈도우 함수 — 전체 유저 정렬 |

코드·DB·시드 데이터·VU 수·지속 시간·엔드포인트 구성은 **전부 고정**한다.

그리고 절대값이 아니라 **규모를 바꿔가며 기울기**를 본다 — 유저 1만 / 5만 / 10만.

## 왜 Flask vs Spring 비교를 안 하는가

기존 `docs/spring-migration-plan.md`의 비교표는 변수가 최소 네 개(커넥션 풀 15 vs 20,
캐시 커버리지, 시나리오 구성, 처리량) 섞여 있어 **어느 변수가 차이를 만들었는지 말할 수 없다.**
게다가 그 측정은 버그 A가 살아있던 상태에서 잰 것이라 숫자 자체를 쓸 수 없다.

## 이 측정이 답하는 질문 두 개

1. **"왜 랭킹을 Redis로 옮겼나요?"** → 규모가 커질 때 기울기가 다르다
2. **"Redis가 죽으면요?"** → `db` 팔이 곧 장애 시 폴백 경로다. 이만큼 느려지지만 서비스는 산다

`db` 팔은 벤치마크용 별도 구현이 아니라 **운영에 이미 있는 Redis 장애 폴백 경로**를
강제로 태우는 것이다 (`RankService.getTop30FromDb()` / `getUserRankFromDb()`).

## 구성

| 파일 | 역할 |
|---|---|
| `seed_scale.sql` | 규모별 시드. 순수 SQL(재귀 CTE) — Python 의존성 없음 |
| `rank_ab.js` | k6 스크립트. `GET /rank` 70% + `GET /rank/users` 30%, VU 30, 60초 |
| `run_benchmark.sh` | 시드 → Redis 워밍 → 6회 측정 자동 실행 |
| `results/` | k6 요약(JSON/TXT), 서버 로그 |

### 시드 설계

유저마다 커밋 수를 1~40건으로 다르게 준다(`user_id % 40 + 1`).
전원 동일하면 `DENSE_RANK` 결과가 전부 1등이 되어 정렬 비용이 현실과 달라진다.
동점자도 자연스럽게 생겨 dense rank HASH가 실제처럼 작아진다(점수 종류 40개).

### Redis 워밍이 필요한 이유

Redis가 비어 있으면 `getTop30()`이 **조용히 DB 폴백을 탄다.**
그러면 두 팔이 같은 걸 재게 되어 측정이 무의미해진다.

그래서 각 규모마다 앱의 실제 스케줄러(`RankingSelfHealingScheduler`) 경로로 Redis를 채운 뒤,
`ZCARD`가 유저 수와 일치하는지 확인하고 서버를 내린다.
측정 중에는 스케줄러 cron을 먼 미래(`0 0 0 1 1 ?`)로 밀어 끼어들지 못하게 한다 —
10만 ZSET 전체를 다시 읽는 배치가 측정 중 돌면 지연이 튄다.

## 실행

```bash
docker compose up -d
brew install k6
bash bench/run_benchmark.sh
```

회차마다 서버를 새로 띄운다. `ranking.redis.enabled`는 기동 시 주입되는 프로퍼티라
한 프로세스에서 두 팔을 섞을 수 없고, 섞더라도 JIT·커넥션풀 워밍 상태가 뒤엉켜
비교가 오염된다.

## 결과

→ [`../docs/측정-결과.md`](../docs/측정-결과.md)
