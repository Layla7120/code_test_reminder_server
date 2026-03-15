import os
from flask.cli import load_dotenv

load_dotenv()

class Config:
    # ── Local (TCP 연결) ──────────────────────────────────────
    db = {
        'user': os.getenv("DB_USER"),
        'password': os.getenv("DB_PASSWORD"),
        'host': os.getenv("DB_HOST"),       # e.g. "127.0.0.1"
        'database': os.getenv("DB_NAME"),
        'port': os.getenv("DB_PORT")         # e.g. "3306"
    }
    SQLALCHEMY_DATABASE_URI = (
        f"mysql+mysqlconnector://{db['user']}:{db['password']}@"
        f"{db['host']}:{db['port']}/{db['database']}"
        f"?charset=utf8mb4&collation=utf8mb4_general_ci"
    )

    # ── Deploy (Unix Socket 연결: GCP Cloud SQL 등) ───────────
    # DB_HOST 환경변수 = Unix 소켓 경로 (e.g. /cloudsql/project:region:instance)
    # db = {
    #     'user': os.getenv("DB_USER"),
    #     'password': os.getenv("DB_PASSWORD"),
    #     'host': os.getenv("DB_HOST"),
    #     'database': os.getenv("DB_NAME")
    # }
    # SQLALCHEMY_DATABASE_URI = (
    #     f"mysql+mysqlconnector://{db['user']}:{db['password']}@/{db['database']}"
    #     f"?unix_socket={db['host']}&charset=utf8mb4&collation=utf8mb4_general_ci"
    # )

    SQLALCHEMY_TRACK_MODIFICATIONS = False

    # ── SQLAlchemy 커넥션 풀 설정 ────────────────────────────
    # MariaDB max_connections 기본값: 151
    # Gunicorn workers 수 × (pool_size + max_overflow) ≤ MariaDB max_connections
    #
    # [부하 테스트 전 - 장애 재현용 (의도적으로 작게 설정)]
    #   pool_size=5, max_overflow=10 → 최대 15개 커넥션 → k6 VU 50+에서 고갈
    #
    # [부하 테스트 후 - 튜닝 결과]
    #   pool_size=10, max_overflow=20 → 최대 30개 커넥션
    #
    SQLALCHEMY_POOL_SIZE = int(os.getenv("SQLALCHEMY_POOL_SIZE", 5))        # 상시 유지 커넥션 수
    SQLALCHEMY_MAX_OVERFLOW = int(os.getenv("SQLALCHEMY_MAX_OVERFLOW", 10)) # 피크 시 추가 커넥션 수
    SQLALCHEMY_POOL_TIMEOUT = int(os.getenv("SQLALCHEMY_POOL_TIMEOUT", 30))  # 커넥션 대기 제한(초) - 초과 시 TimeoutError
    SQLALCHEMY_POOL_RECYCLE = 1800 # 커넥션 재사용 주기(초) - MariaDB wait_timeout 대응

    # ── Flask-Caching 설정 ───────────────────────────────────
    # 랭킹처럼 실시간성이 낮은 데이터는 캐싱으로 DB 요청 자체를 줄임
    # CACHE_TYPE: SimpleCache(단일 프로세스), RedisCache(멀티 프로세스/배포)
    CACHE_TYPE = os.getenv("CACHE_TYPE", "SimpleCache")
    CACHE_DEFAULT_TIMEOUT = int(os.getenv("CACHE_TIMEOUT", 60))  # 캐시 유지 시간(초)

