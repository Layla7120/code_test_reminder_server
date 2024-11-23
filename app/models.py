from sqlalchemy import text
from app import db

class User(db.Model):
    __tablename__ = 'Users'

    user_id = db.Column(db.Integer, primary_key=True, autoincrement=True, nullable=False)
    github_id = db.Column(db.String(255), nullable=False, unique=True)
    repository_name = db.Column(db.String(255), nullable=False)
    active = db.Column(db.String(1), nullable=False, default='y')
    createdAt = db.Column(db.TIMESTAMP, nullable=True, default=text("current_timestamp()"))
    updatedAt = db.Column(db.TIMESTAMP, nullable=True, default=None)

    commits = db.relationship('Commit', back_populates='user')

    def __repr__(self):
        return f"<User(user_id={self.user_id}, github_id={self.github_id}, repository_name={self.repository_name}, active={self.active})>"


class Commit(db.Model):
    __tablename__ = 'Commit'

    commit_id = db.Column(db.Integer, primary_key=True, autoincrement=True, nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('Users.user_id', ondelete='CASCADE', onupdate='NO ACTION'), nullable=False)
    commit_date = db.Column(db.TIMESTAMP, nullable=True)
    commit_url = db.Column(db.String(255), nullable=False)
    title = db.Column(db.String(255), nullable=False)
    level = db.Column(db.String(255), nullable=False)
    sha = db.Column(db.String(255), nullable=False)

    # Define a relationship to the `User` model
    user = db.relationship('User', back_populates='commits')