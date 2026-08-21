# ADR-0005: history 를 살리고 문제당 한 행으로 정의한다

**날짜** 2026-08-21 · **상태** 승인됨(미구현) · **관련** [0001](0001-사용자-삭제를-물리-삭제로-단일화한다.md), `PLAN-db-foundation.md` Phase 5

## 맥락

`history` 는 **쓰기 전용 테이블**이었다. 엔드포인트가 `POST /history` 하나뿐이고
`HistoryRepository.findByUser` 는 **어디서도 호출되지 않는다**(데모 페이지도 안 쓴다).
데이터가 들어가기만 하고 나오지 않았다.

`PLAN-db-foundation.md` Phase 5 는 이 테이블을 *"읽는 코드가 `findByUser` 하나뿐인
지금이 고칠 때"* 라고 적었는데, 정확히는 **읽는 코드가 0개**였다.

측정 결과 이 테이블은 `PLAN-db-foundation.md` §1 의 **파생 사본 4문 테스트 ③번
("읽는 소비자가 있는가")** 을 낙제하고 있었다.

**사용자 확인**: 죽은 코드가 아니라 *"기록 추가하려다 중단한"* 미완성 기능이다.
조회를 만들어 완성한다.

## 결정

**살린다.** 조회 엔드포인트를 만들고, 한 행의 의미를 **문제당 하나**로 정의한다.

```sql
history (
    history_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    problem_num VARCHAR(20) NOT NULL,
    solve_time  INT         NOT NULL,   -- 초 단위. 이전: VARCHAR(10) "HH:MM:SS"
    solved_at   DATETIME(6) NOT NULL,   -- 신규
    CONSTRAINT uk_history_user_problem UNIQUE (user_id, problem_num),
    CONSTRAINT fk_history_user FOREIGN KEY (user_id)
        REFERENCES users(user_id) ON DELETE CASCADE
)
```

**API**: `GET /history?userId=` → `[{problemNum, solveTime, solvedAt}]`, `solvedAt DESC`.
페이징 없음. `historyId` 는 응답에 넣지 않는다.

**기존 데이터는 삭제하고 시작한다.**

## 검토하고 버린 것

**테이블·엔드포인트를 통째로 삭제.**
처음 권고안이었다. 소비자 없는 테이블의 스키마를 정비하는 건 아무도 안 쓸 것을 다듬는
일이기 때문이다. **사용자가 미완성 기능임을 밝혀 철회했다** — 죽은 코드와 미완성 기능은
겉모습이 같지만 처방이 정반대다. 이건 코드만 봐서는 구별할 수 없었다.

**시도당 1행 (제약 없음, 재도전마다 누적).**
버린 이유: 중복 POST 방어가 앱에도 DB 에도 없어 화면에 같은 문제가 여러 번 뜬다.
재도전 이력을 보여줄 화면 계획이 없으면 쓰레기 행을 모으는 것이다.
UNIQUE 는 **DB 가 지킬 수 있는 규칙**이라 이쪽이 `PLAN-db-foundation.md` §2-4 에 맞는다.

**문제당 1행 + 최고 기록만 갱신.**
버린 이유: "더 빠른가" 비교 로직이 늘고, `solve_time` 이 문자열인 상태에서는
그 비교부터 불가능했다.

**`solved_at` 을 NULL 허용하거나 마이그레이션 시각으로 백필.**
버린 이유: **없는 사실을 지어내는 것**이다. 그 시각으로 정렬한 화면은 거짓을 보여준다.
NULL 허용은 정렬 규칙을 매 쿼리마다 따지게 하고 "언제 풀었는지 모르는 기록"이라는
무의미한 상태를 스키마에 영구히 남긴다.

**`solve_time` 을 `VARCHAR` 로 유지.**
버린 이유: 문자열이면 DB 가 `"1:05:00"`·`"01:05:00"`·`"65분"` 을 전부 받아들인다.
정렬·평균·비교가 안 되고, 지금 `POST /history` 에는 형식 검증이 **한 줄도 없다**
(`SaveHistoryRequest` 에 `@Valid` 조차 없음). `INT` 는 타입 자체가 검증이다.
엔티티 주석이 *"하위호환 리스크로 타입 변환 보류"* 라고 적었는데,
**실사용자가 없으므로 그 리스크는 지금 존재하지 않는다.**

## 결과

- `endpoint-allowlist.txt` 에서 **심각도 "중"** 한 줄이 사라진다(`HistoryController#saveHistory`).
  `verifyEndpointCoverage` 가 이미 그 게이트다 — 남은 개수는 그 파일이 진실 원천이라
  여기 적지 않는다.
- `PLAN-db-foundation.md` Phase 5 의 `history` 항목이 여기로 흡수된다.
- **잃는 것**: 마이그레이션이 `DELETE` → `ALTER` 순서라 **되돌릴 수 없다.**
  마이그레이션 파일 주석에 그 사실을 적는다.
  dev 볼륨에 살릴 `history` 데이터가 없다는 전제로 진행한다.
- **잃는 것**: 표시 형식(`"01:05:00"`)을 클라이언트가 만들어야 한다.
  서버가 표시 형식을 정하면 화면이 둘이 될 때 서버를 고쳐야 하므로 의도한 방향이다.

## 어떻게 지켜지나

- `verifyEndpointCoverage` — `GET /history` 에 HTTP 테스트가 없으면 빌드가 깨진다
- `SchemaContractTest` — `solve_time` 타입이 `int`, `solved_at` 이 `NOT NULL`,
  `uk_history_user_problem` 존재
- `HistoryApiTest` (신규) — 같은 문제 재등록 시 행이 늘지 않고 갱신되는가, 정렬 순서
