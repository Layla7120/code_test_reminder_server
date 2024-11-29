import os

from flask import Flask
from flask_smorest import Api

from app.db import db
from app.db.config import Config
from app.extensions import bcrypt

from app.routes.user_routes import user_bp
from app.routes.commit_routes import commits_bp
from app.routes.github_routes import github_bp
from app.routes.group_routes import group_bp

def create_app():
    app = Flask(__name__)

    # OpenAPI configuration
    app.config["API_TITLE"] = "코테 독촉기"
    app.config["API_VERSION"] = "v1"
    app.config["OPENAPI_VERSION"] = "3.1.3"
    app.config["OPENAPI_URL_PREFIX"] = "/docs"
    app.config['OPENAPI_SWAGGER_UI_PATH'] = "/swagger-ui"
    app.config['OPENAPI_SWAGGER_UI_URL'] = "https://cdn.jsdelivr.net/npm/swagger-ui-dist/"

    api = Api(app)

    # Load database configuration
    app.config.from_object(Config)

    # Initialize extensions
    db.init_app(app)

    # Configure your app (if needed)
    app.config['SECRET_KEY'] = os.getenv("SECRET_KEY")

    # Initialize bcrypt with the app
    bcrypt.init_app(app)

    # 라우트 등록
    api.register_blueprint(user_bp, url_prefix="/users")
    api.register_blueprint(github_bp, url_prefix="/github")
    api.register_blueprint(commits_bp, url_prefix="/commits")
    api.register_blueprint(group_bp, url_prefix="/group")

    return app
