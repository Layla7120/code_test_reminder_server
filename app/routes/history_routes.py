from flask_smorest import Blueprint
from marshmallow import Schema, fields
from sqlalchemy.exc import IntegrityError

from app import db
from app.error_handler import generate_error
from app.services.history_service import HistoryService

# Blueprint
history_bp = Blueprint('History', __name__)

# ----- Schemas -----
class HistoryRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID")
    problem_num = fields.String(required=True, description="Problem number")
    solve_time = fields.String(required=True, description="Time taken to solve the problem (e.g., '00:05:30')")

class HistoryResponseSchema(Schema):
    history_id = fields.Integer(description="History ID")
    user_id = fields.Integer(description="User ID")
    problem_num = fields.String(description="Problem number")
    solve_time = fields.String(description="Time taken to solve the problem")
    
    
@history_bp.route('', methods=['POST'])
@history_bp.arguments(HistoryRequestSchema, location='json')
@history_bp.response(201, HistoryResponseSchema)
def create_history(history_data):
    """
    Create a new history record.

    Args:
        history_data (dict): Request data containing `user_id`, `problem_num`, and `solve_time`.

    Returns:
        dict: Newly created history record details.

    Raises:
        generate_error: If a database error occurs.
    """
    try:
        # Create a new history record
        history = HistoryService.create_history(
            user_id=history_data['user_id'],
            problem_num=history_data['problem_num'],
            solve_time=history_data['solve_time']
        )

        return {
            "history_id": history.history_id,
            "user_id": history.user_id,
            "problem_num": history.problem_num,
            "solve_time": history.solve_time
        }

    except IntegrityError:
        db.session.rollback()
        generate_error(500, "A database error occurred while creating the history record.")

