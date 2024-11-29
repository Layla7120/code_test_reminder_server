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
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class UserResponseSchema(Schema):
    user_id = fields.Integer(description="User ID")
    github_id = fields.String(description="GitHub ID of the user")
    repository_name = fields.String(description="Repository name of the user")

# ----- Routes -----

@user_bp.route('/', methods=['GET'])
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
        "github_id": user.github_id,
        "repository_name": user.repository_name
    }


@user_bp.route('/', methods=['POST'])
@user_bp.arguments(UserRequestSchema, location='json')
@user_bp.response(201, UserResponseSchema)
def create_user(user_data):
    """
    Create a new user.

    Args:
        user_data (dict): Request data containing `github_id` and `repository_name`.

    Returns:
        dict: Newly created user details.

    Raises:
        generate_error: If a user with the given GitHub ID already exists or a database error occurs.
    """
    # Check if the GitHub ID already exists
    existing_user = UserService.get_user_by_github_id(user_data['github_id'])
    if existing_user:
        generate_error(409, f"User with GitHub ID '{user_data['github_id']}' already exists.")

    try:
        # Create a new user
        user = UserService.create_user(user_data['github_id'], user_data['repository_name'])
        return {
            "user_id": user.user_id,
            "github_id": user.github_id,
            "repository_name": user.repository_name
        }

    except IntegrityError:
        db.session.rollback()
        generate_error(500, "A database error occurred while creating the user.")
