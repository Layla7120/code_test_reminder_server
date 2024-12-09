# 코테 독촉기 Server

## 기술 스택
- Flask: 웹 프레임워크 
- Flask-Smorest: OpenAPI 지원 및 유효성 검사 
- Flask-SQLAlchemy: 데이터베이스 ORM 
- Marshmallow: 스키마 유효성 검사 
- MariaDB: 기본 데이터베이스
---
## API 문서
API의 자세한 스펙은 Swagger UI를 통해 확인할 수 있습니다.

Swagger URL
- Swagger UI: /docs/swagger-ui

--- 
## 프로젝트 구조

```bash 
Code_Test_Reminder_Server/
├── Dockerfile
├── README.md
├── app
│ ├── __init__.py
│ ├── constants.py
│ ├── db
│ │ ├── __init__.py
│ │ └── config.py
│ ├── error_handler.py
│ ├── extensions.py                  # bcrypt
│ ├── models.py                      # SQLAlchemy 모델
│ ├── routes
│ │ ├── commit_routes.py
│ │ ├── github_routes.py
│ │ ├── group_routes.py
│ │ ├── rank_routes.py
│ │ └── user_routes.py
│ └── services                       # 데이터베이스 로직들
│     ├── commit_service.py
│     ├── github_service.py
│     ├── group_service.py
│     ├── participate_service.py
│     └── user_service.py
├── requirements.txt
├── run.py                           # 애플리케이션 실행 엔트리 포인트
└── tests
    └── test_user.py
```
