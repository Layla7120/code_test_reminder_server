# 백엔드 설계 자료

## Knowledge

- [Alistair Cockburn, "Hexagonal Architecture (Ports and Adapters)"](https://alistair.cockburn.us/hexagonal-architecture/)
  포트/어댑터의 원전. 언제 볼 것: 인터페이스를 어디에 그을지 정할 때.
- [Martin Fowler, "Mocks Aren't Stubs"](https://martinfowler.com/articles/mocksArentStubs.html)
  테스트 더블의 종류와, 더블의 위치가 무엇을 검증하는지 바꾼다는 논점.
  언제 볼 것: "이 테스트가 실제로 뭘 증명하나"가 애매할 때.
- [GitHub REST API — Using pagination](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)
  `per_page`·`page`·`Link` 헤더 규약. 언제 볼 것: 수집 루프의 종료 조건을 정할 때.
- [Spring Framework — MockRestServiceServer javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/web/client/MockRestServiceServer.html)
  **확인함(2026-08-21)**: `bindTo(RestClient.Builder)` 는 6.1 부터. 이 프로젝트는 7.0.6.
  단, 레퍼런스 문서는 전송 계층 테스트에는 mock web server 를 권장한다 — 그 단서를 같이 읽을 것.
- *Designing Data-Intensive Applications* — Kleppmann. 5장(복제)·7장(트랜잭션)·3장(저장 엔진)
  언제 볼 것: 파생 사본과 일관성. `docs/archive/PLAN-db-foundation.md` §6 이 이미 매핑해뒀다.
- MySQL 공식 문서 15.6.2(InnoDB 인덱스 구조), 11.2(날짜/시간), 10.3(charset)
  언제 볼 것: 클러스터드 인덱스가 PK 를 세컨더리에 복제하는 비용, DATETIME vs TIMESTAMP.

## Wisdom (Communities)

- 아직 정하지 않음. 사용자에게 커뮤니티 참여 의사를 물어본 적이 없다.
  다음 세션에 확인할 것 — 원치 않으면 여기에 기록하고 다시 제안하지 않는다.

## Gaps

- **"언제 대사(reconciliation) 잡을 두는가"** 에 대한 신뢰할 만한 1차 자료를 아직 못 찾았다.
  현재 답("원천과 사본이 다른 트랜잭션 경계에 있을 때")은 근거 문서 없이 정리한 것이다.
