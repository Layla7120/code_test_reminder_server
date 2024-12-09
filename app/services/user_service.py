from datetime import datetime

from sqlalchemy.dialects.mysql import insert
from sqlalchemy.exc import IntegrityError

from app import db, generate_error
from app.models import User


class UserService:
    @staticmethod
    def get_user_by_user_id(user_id):
        return User.query.get(user_id)

    @staticmethod
    def get_user_by_nick_name(nick_name):
        return db.session.query(User).filter_by(nick_name=nick_name).first()

    @staticmethod
    def create_or_get_user(nick_name, github_id, repository_name):
        # 기존 데이터 조회
        user = db.session.query(User).filter_by(nick_name=nick_name).first()

        if user:
            user.updatedAt = datetime.utcnow()
            db.session.commit()
            return user  # 이미 존재하는 경우 반환

        # 없으면 새로 생성
        new_user = User(
            nick_name=nick_name,
            github_id=github_id,
            repository_name=repository_name
        )
        db.session.add(new_user)
        db.session.commit()

        return new_user

    @staticmethod
    def delete_user(user_id):
        try:
            user = User.query.get(user_id)

            if user:
                db.session.delete(user)
                db.session.commit()
                print(f"User deleted {user.user_id}")
            else:
                print("User not found")

        except IntegrityError as e:
            db.session.rollback()  # 오류 발생 시 롤백
            print(f"IntegrityError: {e}")
        except Exception as e:
            db.session.rollback()
            print(f"Error: {e}")