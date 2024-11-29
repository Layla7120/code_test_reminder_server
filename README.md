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
├── app/
│   ├── __init__.py          # 앱 팩토리 및 확장 설정
│   ├── models.py            # SQLAlchemy 모델
│   ├── constants.py         
│   ├── extensions.py        # bcrypt
│   ├── error_handler.py     
│   ├── routes/
│   │   ├── commit_routes.py
│   │   ├── github_routes.py 
│   │   ├── group_routes.py  
│   │   ├── user_routes.py   
│   ├── services/            # 데이터베이스 로직들
│   │   ├── commit_service.py 
│   │   ├── github_service.py  
│   │   ├── group_service.py  
│   │   ├── participate_service.py  
│   │   ├── user_service.py  
├── tests/
│   ├── test_user.py         # API 단위 테스트
├── images/                   # README용 images   
├── config.py                
├── run.py                   # 애플리케이션 실행 엔트리 포인트
├── requirements.txt    
├── Dockerfile     
├── README.md      
```
---
# API 명세서

## User

- [x] [GET] 사용자 정보 가져오기 
  - `/users/?id=<user_id>`
  - 응답 예시 (성공):
    ```json
    {
      "user_id": 1,
      "github_id": "Layla7120",
      "repository_name": "Code_Tests"
    }
    ```
- [x] [POST] 사용자 생성하기
  - `/users/`
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
    
## Github
- [x] [GET] GitHub Repo 확인 및 정보 가져오기
  - `/github/repos?github_id=<github_id>&repository_name=<repository_name>`
  - 응답 예시 (성공):
    ```json
        [
          {
            "description": "This is an auto push repository for Baekjoon Online Judge created with [BaekjoonHub](https://github.com/BaekjoonHub/BaekjoonHub).",
            "html_url": "https://github.com/Layla7120/Code_Tests",
            "name": "Code_Tests"
          }
        ]
    ```
- [x] [GET] Github 에서 Commit 내역을 받아오기
  - `/commits/?user_id=<user_id>&github_id=<github_id>&repository_name=<repository_name>`
  - 응답 예시 (성공):
    ```json
    [
      {
        "author": {
          "date": "2024-11-17T06:09:42Z",
          "email": "crispylemon7120@gmail.com",
          "name": "Layla Oh"
        },
        "description": "No description",
        "html_url": "https://github.com/Layla7120/Code_Tests/commit/...",
        "message": "[D4] Title: 격자판의 숫자 이어 붙이기, Time: 603 ms, Memory: 66,288 KB -BaekjoonHub",
        "sha": "..."
      } 
    ]
    ```

## Commits
- [x] [POST] Commit 내역을 database에 저장하기
  - `/commits/`
  - 응답 예시 (성공):
    ```json
    {
      "fetched_commits": 9,
      "message": "Successfully updated 9 commits."
    }
    ```
  
- [x] [GET] 데이터베이스에서 최근 7일간 커밋 내역 조회
  - `/commits/activity?user_id=<user_id>`
  - 응답 예시 (성공):
    ```json
      {
        "2024-11-17": {
          "committed": true,
          "weekday": "Sunday"
        },
        "2024-11-18": {
          "committed": false,
          "weekday": "Monday"
        },
        "2024-11-19": {
          "committed": false,
          "weekday": "Tuesday"
        },
        "2024-11-20": {
          "committed": true,
          "weekday": "Wednesday"
        },
        "2024-11-21": {
          "committed": false,
          "weekday": "Thursday"
        },
        "2024-11-22": {
          "committed": false,
          "weekday": "Friday"
        },
        "2024-11-23": {
          "committed": false,
          "weekday": "Saturday"
        }
      }
      ```

## GROUP 

- [x] [GET] User_id 로 Group info 가져오기
  - `/group/info?user_id=<user_id>`
  - 응답 예시 (성공):
    ```json
    [
      {
        "group_commits": [
          [
            {
              "commit_count": 9,
              "difference_from_prev": null,
              "github_id": "Layla7120",
              "rank": 1,
              "user_id": 16
            },
            {
              "commit_count": 5,
              "difference_from_prev": 4,
              "github_id": "dangeunii",
              "rank": 2,
              "user_id": 18
            }
          ]
        ],
        "group_id": 15,
        "group_name": "코테"
      }
    ]
      ```
- [x] [POST] Create Group 
  - `/group/`
  - 요청 JSON:
    ```json
    {
      "group_name": "코테2",
      "group_pw": "1234",
      "member_maxCnt": 5,
      "owner_user_id": 9
    }
    ```
  - 응답 예시 (성공):
    ```json
    {
      "group_name": "코테2",
      "member_maxCnt": 5
    }
    ```

- [x] [POST] Group 에 member 추가
  - `/group/member`
  - 요청 JSON:
    ```json
    {
    "user_id": 17,
    "group_id": 15,
    "group_pw": "1234"
    }
    ```
  - 응답 예시 (성공):
    ```json
    {
    "group_name": "코테",
    "user_id": 17
    }
    ```
    
- [x] [GET] Group name 으로 Group 검색하기
  - `/group/search?group_name='코'` 
  - 응답 예시 (성공):
    ```json
    [
      {
        "group_id": 15,
        "group_name": "코테",
        "group_pw": "$2b$12$sRu7krVeWXvEKnHbeDpBF.hL6/Y6FjOMmIORa8Jrl2sxNxyYWXNaK",
        "member_counter": 3,
        "member_maxCnt": 5
      }
    ]
      ```