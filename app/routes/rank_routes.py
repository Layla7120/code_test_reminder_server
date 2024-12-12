from flask import jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from marshmallow.validate import Range

from app.error_handler import handle_errors
from app.services.commit_service import CommitService

rank_bp = Blueprint('Rank', __name__)

class UserCommitsRequestSchema(Schema):
    """Schema for storing commits."""
    user_id = fields.Integer(required=True, description="User ID to fetch")

# ----- SCHEMAS -----
class RankResponseSchema(Schema):
    group_name = fields.String(required=True, description="Name of the group")
    group_pw = fields.String(required=True, description="Password of the group")
    member_maxCnt = fields.Integer(
        description="Max count of members for group",
        load_default=5,
        validate=Range(min=1, max=30)
    )
    owner_user_id = fields.Integer(required=True, description="Owner User ID", dump_default=5)


# ----- ROUTES -----

@rank_bp.route('', methods=['GET'])
@rank_bp.response(200, RankResponseSchema)
@handle_errors
def get_rank_public_info():
    """Retrieve Overall Ranking"""

    commit_infos = CommitService.get_info_for_rank_view()
    return jsonify(commit_infos)


@rank_bp.route('/users', methods=['GET'])
@rank_bp.arguments(UserCommitsRequestSchema, location='query')
@rank_bp.response(200)
def get_rank(user_data):
    """Get user rank info"""
    user_id = user_data["user_id"]
    query_results = CommitService.get_user_rank(user_id)
    return jsonify(query_results)