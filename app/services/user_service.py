from app import db
from app.models import User


class UserService:
    @staticmethod
    def get_user_by_user_id(user_id):
        return User.query.get(user_id)

    @staticmethod
    def get_user_by_github_id(github_id):
        return User.query.get(github_id)

    @staticmethod
    def create_user(nick_name, github_id, repository_name):
        new_user = User(nick_name=nick_name, github_id=github_id, repository_name=repository_name)
        db.session.add(new_user)
        db.session.commit()
        return new_user

    @staticmethod
    def delete_user(user_id):
        user = User.query.get(user_id)
        if user:
            db.session.delete(user)
            db.session.commit()
