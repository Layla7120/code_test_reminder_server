from flask_smorest import Blueprint
from marshmallow import Schema, fields
from sqlalchemy.exc import IntegrityError

from app import db
from app.error_handler import generate_error
from app.services.user_service import UserService

# Blueprint
user_bp = Blueprint('User', __name__)

# ----- Schemas -----
class UserQuerySchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to fetch")

class UserRequestSchema(Schema):
    nick_name = fields.String(required=True, description="Nickname of the user")
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class UserResponseSchema(Schema):
    user_id = fields.Integer(description="User ID")
    nick_name = fields.String(required=True, description="Nickname of the user")
    github_id = fields.String(description="GitHub ID of the user")
    repository_name = fields.String(description="Repository name of the user")
    createdAt = fields.DateTime(description="Creation timestamp of the user")

# ----- Routes -----

@user_bp.route('', methods=['GET'])
@user_bp.arguments(UserQuerySchema, location='query')
@user_bp.response(200, UserResponseSchema)
def get_users(query_args):
    """
    Retrieve a user by ID.

    Args:
        query_args (dict): Query arguments containing the `user_id`.

    Returns:
        dict: User details if found.

    Raises:
        generate_error: If the user is not found or `user_id` is missing.
    """
    user_id = query_args['user_id']

    user = UserService.get_user_by_user_id(user_id)
    if not user:
        generate_error(404, "User not found.")

    return {
        "user_id": user.user_id,
        "nick_name": user.nick_name,
        "github_id": user.github_id,
        "repository_name": user.repository_name
    }


@user_bp.route('', methods=['POST'])
@user_bp.arguments(UserRequestSchema, location='json')
@user_bp.response(201, UserResponseSchema)
def login_user(user_data):
    """
    Login the user - create if not in db

    Args:
        user_data (dict): Request data containing `nick_name`, `github_id` and `repository_name`.

    Returns:
        dict: Newly created user details.

    Raises:
        generate_error: If a user with the given GitHub ID already exists or a database error occurs.
    """
    try:
        if not user_data:
            return {"error": "No data received"}, 400

        user = UserService.create_or_get_user(user_data['nick_name'], user_data['github_id'], user_data['repository_name'])
        return user

    except IntegrityError:
        db.session.rollback()
        generate_error(500, "A database error occurred while creating the user.")

@user_bp.route('/nick_name', methods=['GET'])
@user_bp.arguments(UserRequestSchema, location='query')
@user_bp.response(200, description="Nick name can be used")
def check_user(query_args):
    """
    Check if a nick_name is already used.

    Args:
        query_args (dict): Query arguments containing the `nick_name`.

    Returns:
        Response 200 if the nick_name can be used.

    Raises:
        generate_error: If the nick_name is already used.
    """
    nick_name = query_args['nick_name']
    github_id = query_args['github_id']
    repository_name = query_args['repository_name']

    user = UserService.get_user_by_nick_name(nick_name)
    if not user:
        return {"message": f"New user {nick_name} can be added" }, 200
    elif user.github_id == github_id and user.repository_name == repository_name:
        return {"message": f"{nick_name} is logging in"}, 200
    elif user:
        return generate_error(404, "Nick name already used.")

# TODO: user github repository 변경, user 닉네임 변경 sql update 할 때 둘 중 하나만 받아도 업데이트 하게
#  - UPDATE if nickName != null || nickName != "" if repository != null 또는 repository != ""