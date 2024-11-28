from sqlalchemy import text
from app import db

class User(db.Model):
    __tablename__ = 'Users'

    user_id = db.Column(db.Integer, primary_key=True, autoincrement=True, nullable=False)
    github_id = db.Column(db.String(255), nullable=False, unique=True)
    repository_name = db.Column(db.String(255), nullable=False)
    active = db.Column(db.String(1), nullable=False, default='y')
    createdAt = db.Column(db.TIMESTAMP, nullable=True, server_default=text("current_timestamp()"))
    updatedAt = db.Column(db.TIMESTAMP, nullable=True, onupdate=text("current_timestamp()"))

    # Relationships
    commits = db.relationship('Commit', back_populates='user', cascade="all, delete-orphan")
    groups = db.relationship('Group', back_populates='user', cascade="all, delete-orphan")
    participation = db.relationship('Participate', back_populates='user', cascade="all, delete-orphan")

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

    # Relationship with User
    user = db.relationship('User', back_populates='commits')

    def __repr__(self):
        return f"<Commit(commit_id={self.commit_id}, title={self.title}, level={self.level}, user_id={self.user_id})>"


class Group(db.Model):
    __tablename__ = 'Group'

    group_id = db.Column(db.Integer, primary_key=True, autoincrement=True, nullable=False)
    group_name = db.Column(db.String(255), nullable=False, unique=True)
    group_pw = db.Column(db.String(255), nullable=True)
    member_maxCnt = db.Column(db.Integer, nullable=True, default=30)
    created_at = db.Column(db.TIMESTAMP, nullable=False, server_default=text("current_timestamp()"))
    owner = db.Column(db.Integer, db.ForeignKey('Users.user_id', onupdate="NO ACTION"), nullable=False)

    # Relationships
    user = db.relationship('User', back_populates='groups')
    participants = db.relationship('Participate', back_populates='group', cascade="all, delete-orphan")

    def __repr__(self):
        return f"<Group(group_id={self.group_id}, group_name={self.group_name}, owner={self.owner})>"


class Participate(db.Model):
    __tablename__ = 'Participate'

    group_id = db.Column(db.Integer, db.ForeignKey('Group.group_id', ondelete='CASCADE', onupdate='NO ACTION'), primary_key=True, nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('Users.user_id', ondelete='CASCADE', onupdate='NO ACTION'), primary_key=True, nullable=False)

    # Relationships
    group = db.relationship('Group', back_populates='participants')
    user = db.relationship('User', back_populates='participation')

    def __repr__(self):
        return f"<Participate(group_id={self.group_id}, user_id={self.user_id})>"
