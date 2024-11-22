# 코테 독촉기 Server

## 기술 스택
- Flask: 웹 프레임워크 
- Flask-Smorest: OpenAPI 지원 및 유효성 검사 
- SQLAlchemy: 데이터베이스 ORM 
- Marshmallow: 스키마 유효성 검사 
- MariaDB: 기본 데이터베이스
---
## API 문서
API의 자세한 스펙은 Swagger UI를 통해 확인할 수 있습니다.

Swagger URL
- Swagger UI: http://127.0.0.1:5000/docs/swagger-ui

--- 
## 프로젝트 구조

```bash 
Code_Test_Reminder_Server/
├── app/
│   ├── __init__.py          # 앱 팩토리 및 확장 설정
│   ├── models.py            # SQLAlchemy 모델
│   ├── routes/
│   │   ├── user_routes.py   # 사용자 관련 라우트(Blueprint)
│   ├── services/
│   │   ├── user_service.py  # 사용자 관련 데이터베이스 로직
├── tests/
│   ├── test_user.py         # API 단위 테스트
├── config.py                
├── run.py                   # 애플리케이션 실행 엔트리 포인트
├── requirements.txt         
├── README.md                
```

# 구현 내용
- [x] 사용자 GET - /users/?id=<user_id>
  - 응답 예시 (성공):
    ```json
    {
      "user_id": 1,
      "github_id": "Layla7120",
      "repository_name": "Code_Tests"
    }
    ```
- [x] 사용자 POST - /users/
  - 요청 JSON:
    ```json
    {
      "github_id": "Layla7120",
      "repository_name": "Code_Tests"
    }
    ```
    - 응답 예시 (성공):
    ```json
    {
      "user_id": 1,
      "github_id": "Layla7120",
      "repository_name": "Code_Tests"
    }
    ```