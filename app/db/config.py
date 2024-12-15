import os
from flask.cli import load_dotenv

load_dotenv()

class Config:
    local
    db = {
        'user': os.getenv("DB_USER"),
        'password': os.getenv("DB_PASSWORD"),
        'host': os.getenv("DB_HOST"),
        'database': os.getenv("DB_NAME"),
        'port': os.getenv("DB_PORT")
    }
    SQLALCHEMY_DATABASE_URI = f"mysql+mysqlconnector://{db['user']}:{db['password']}@" \
                              f"{db['host']}:{db['port']}/{db['database']}?charset=utf8mb4&collation=utf8mb4_general_ci"
    SQLALCHEMY_TRACK_MODIFICATIONS = False

    # deploy
    # db = {
    #     'user': os.getenv("DB_USER"),
    #     'password': os.getenv("DB_PASSWORD"),
    #     'host': os.getenv("DB_HOST"),
    #     'database': os.getenv("DB_NAME")
    # }
    # SQLALCHEMY_DATABASE_URI = f"mysql+mysqlconnector://{db['user']}:{db['password']}@/{db['database']}"\
    #     f"?unix_socket={db['host']}&charset=utf8mb4&collation=utf8mb4_general_ci"
    #
    # SQLALCHEMY_TRACK_MODIFICATIONS = False

