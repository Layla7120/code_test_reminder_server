class Config:
    db = {
        'user': 'root',
        'password': '1234',
        'host': 'localhost',
        'port': 3306,
        'database': 'code_test_app'
    }
    SQLALCHEMY_DATABASE_URI = f"mysql+mysqlconnector://{db['user']}:{db['password']}@" \
             f"{db['host']}:{db['port']}/{db['database']}?charset=utf8mb4&collation=utf8mb4_general_ci"
    SQLALCHEMY_TRACK_MODIFICATIONS = False