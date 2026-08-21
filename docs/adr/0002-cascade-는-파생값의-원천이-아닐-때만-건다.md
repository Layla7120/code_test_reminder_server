# ADR-0002: CASCADE 는 파생값의 원천이 아닐 때만 건다

**날짜** 2026-08-21 · **상태** 승인됨 · **관련** [0001](0001-사용자-삭제를-물리-삭제로-단일화한다.md), [0006](0006-member-counter-대사를-두지-않는다.md)

## 맥락

[ADR-0001](0001-사용자-삭제를-물리-삭제로-단일화한다.md) 로 유저 행을 실제로 지우게 됐다.
그러려면 `commits`·`history`·`user_monthly_score`·`participate` 가 먼저 사라져야 한다.

현재 `V1__baseline.sql` 의 FK 는 **전부 `ON DELETE` 절이 없다** = RESTRICT.
구 docs/삭제-명세.md §6-2 가 이 상태를 기록해뒀다 — Alembic 시절 스키마는 CASCADE 였고
Flyway 베이스라인은 RESTRICT 라, **두 스키마가 어긋난 채 남아 있었다.**

`docs/archive/PLAN-db-foundation.md` §2-4 의 원칙은 *"DB 가 스스로 지킬 수 있는 규칙은 전부 DDL 로"* 다.
그 원칙을 그대로 따르면 전부 CASCADE 다. **그런데 한 곳에서 조용히 깨진다.**

## 결정

FK 마다 갈라서 건다.

| FK | 정책 | 이유 |
|---|---|---|
| `commits.user_id` | **CASCADE** | 유저에 종속된 소유 데이터. 다른 파생값의 원천이 아니다 |
| `history.user_id` | **CASCADE** | 위와 같다 |
| `user_monthly_score.user_id` | **CASCADE** | `commits` 에서 파생되지만 **이 행을 원천 삼는 것이 없다** |
| `participate.user_id` | **RESTRICT 유지** | ⚠️ `groups.member_counter` 가 이 행들에서 파생된다 |
| `groups.owner_id` | **RESTRICT 유지** | CASCADE 면 오너 삭제가 그룹을 통째로 지운다 |

**일반 규칙**: *CASCADE 는 그 행이 다른 파생값의 원천이 아닐 때만 안전하다.*

## 검토하고 버린 것

**전부 CASCADE.**
버린 이유: `participate` 에서 깨진다. CASCADE 로 걸면 `GroupService.leaveGroup` 을
거치지 않고 행이 사라져 **`member_counter` 가 실제 인원보다 큰 채로 남는다.**
[ADR-0006](0006-member-counter-대사를-두지-않는다.md) 에서 "대사를 두지 않는다"고 정한
바로 그 드리프트를, DB 가 만들어내게 된다.

RESTRICT 로 두면 `leaveGroup` 을 빠뜨렸을 때 **FK 위반으로 즉시 터진다.**
조용한 손상 대신 시끄러운 실패다.

**전부 앱에서 명시 삭제.**
버린 이유: 지우는 순서를 앱이 기억해야 하고, 새 테이블이 생기면 그 코드를 고쳐야 한다.
DB 가 지킬 수 있는 것을 앱으로 내리는 방향이라 §2-4 원칙에 역행한다.

## 결과

- `UserService.deleteUser` 에서 `userMonthlyScoreRepository.deleteAllByUserId(userId)`
  한 줄과 그 위 주석 2줄이 사라진다. CASCADE 가 대신한다.
- `participate` 만 앱이 책임진다. `leaveGroup` 이 유일한 경로라는 게 **DDL 로 표현된다.**
- **잃는 것**: 삭제 정책이 두 가지가 되어 "왜 이건 CASCADE 고 이건 아닌가"를
  설명해야 한다. 그 설명이 이 문서이고, `SchemaContractTest` 가 그걸 코드로 고정한다.

## 어떻게 지켜지나

`SchemaContractTest` 가 `information_schema.REFERENTIAL_CONSTRAINTS.DELETE_RULE` 을 읽어
위 표와 대조한다. 나중에 누가 편의로 `participate` 를 CASCADE 로 바꾸면 빨간불이 난다.

> **구현 주의**: MySQL 은 `ON DELETE` 절이 없는 FK 를 `RESTRICT` 가 아니라 **`NO ACTION`**
> 으로 보고한다(InnoDB 에서 동작은 같다). 기대값을 `'RESTRICT'` 로 쓰면 바로 깨진다.
> 테스트를 먼저 쓰고 실제 출력을 본 뒤 기대값을 맞추되, `AGENTS.md` §2-1 대로
> **기대값을 실제에 맞추기 전에 본문이 맞는지 먼저 확인한다.**
