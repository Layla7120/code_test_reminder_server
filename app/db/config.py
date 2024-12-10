import os
from flask.cli import load_dotenv

load_dotenv()

class Config:
    #local
    # db = {
    #     'user': os.getenv("DB_USER"),
    #     'password': os.getenv("DB_PASSWORD"),
    #     'host': os.getenv("DB_HOST"),
    #     'database': os.getenv("DB_NAME"),
    #     'port': os.getenv("DB_PORT")
    # }
    # SQLALCHEMY_DATABASE_URI = f"mysql+mysqlconnector://{db['user']}:{db['password']}@" \
    #                           f"{db['host']}:{db['port']}/{db['database']}?charset=utf8mb4&collation=utf8mb4_general_ci"
    # SQLALCHEMY_TRACK_MODIFICATIONS = False

    # deploy
    db = {
        'user': os.getenv("DB_USER"),
        'password': os.getenv("DB_PASSWORD"),
        'host': os.getenv("DB_HOST"),
        'database': os.getenv("DB_NAME")
    }
    SQLALCHEMY_DATABASE_URI = f"mysql+mysqlconnector://{db['user']}:{db['password']}@/{db['database']}"\
        f"?unix_socket={db['host']}&charset=utf8mb4&collation=utf8mb4_general_ci"

    SQLALCHEMY_TRACK_MODIFICATIONS = False


# import os
#
# import sqlalchemy
#
#
# def connect_unix_socket() -> sqlalchemy.engine.base.Engine:
#     """Initializes a Unix socket connection pool for a Cloud SQL instance of MySQL."""
#     # Note: Saving credentials in environment variables is convenient, but not
#     # secure - consider a more secure solution such as
#     # Cloud Secret Manager (https://cloud.google.com/secret-manager) to help
#     # keep secrets safe.
#     db_user = os.environ["DB_USER"]  # e.g. 'my-database-user'
#     db_pass = os.environ["DB_PASS"]  # e.g. 'my-database-password'
#     db_name = os.environ["DB_NAME"]  # e.g. 'my-database'
#     unix_socket_path = os.environ[
#         "INSTANCE_UNIX_SOCKET"
#     ]  # e.g. '/cloudsql/project:region:instance'
#
#     pool = sqlalchemy.create_engine(
#         # Equivalent URL:
#         # mysql+pymysql://<db_user>:<db_pass>@/<db_name>?unix_socket=<socket_path>/<cloud_sql_instance_name>
#         sqlalchemy.engine.url.URL.create(
#             drivername="mysql+pymysql",
#             username=db_user,
#             password=db_pass,
#             database=db_name,
#             query={"unix_socket": unix_socket_path},
#         ),
#         # ...
#     )
#     return pool
