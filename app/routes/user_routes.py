from flask import abort
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from sqlalchemy.exc import IntegrityError

from app import db
from app.models import User
from app.services.user_service import UserService

user_bp = Blueprint('user', __name__)

class UserQuerySchema(Schema):
    id = fields.Integer(required=True, description="User ID to fetch")

class UserRequestSchema(Schema):
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class UserResponseSchema(Schema):
    user_id = fields.Integer()
    github_id = fields.String()
    repository_name = fields.String()

@user_bp.route('/', methods=['GET'])
@user_bp.arguments(UserQuerySchema, location='query')
@user_bp.response(200, UserResponseSchema)
def get_users(query_args):
    """Retrieve a user by ID"""
    user_id = query_args['id']
    if not user_id:
        abort(400, description="Missing required query parameter: id")

    user = UserService.get_user_by_id(user_id)
    if user:
        return {
            "user_id": user.user_id,
            "github_id": user.github_id,
            "repository_name": user.repository_name
        }

    abort(404, description="User not found")


@user_bp.route('/', methods=['POST'])
@user_bp.arguments(UserRequestSchema, location='json')
@user_bp.response(201, UserResponseSchema)
def create_user(user_data):
    """Create a new user"""
    # Check if the github_id already exists
    existing_user = User.query.filter_by(github_id=user_data['github_id']).first()
    if existing_user:
        abort(409, description=f"User with github_id '{user_data['github_id']}' already exists.")

    try:
        # Create new user
        user = UserService.create_user(user_data['github_id'], user_data['repository_name'])
        return {
            "user_id": user.user_id,
            "github_id": user.github_id,
            "repository_name": user.repository_name
        }

    except IntegrityError:
        db.session.rollback()
        abort(500, description="A database error occurred while creating the user.")


