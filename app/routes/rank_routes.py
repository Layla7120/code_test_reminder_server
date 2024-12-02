# TODO: [GET] /rank — top 30 , 랭킹 화면에서 사용 (전체 사용자에 대한 rank 제공)

from flask import jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from marshmallow.validate import Range

from app.error_handler import handle_errors
from app.services.commit_service import CommitService

rank_bp = Blueprint('Rank', __name__)

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

@rank_bp.route('/', methods=['GET'])
@rank_bp.response(200, RankResponseSchema)
@handle_errors
def get_rank_public_info():
    """Retrieve Overall Ranking"""

    commit_infos = CommitService.get_info_for_rank_view()

    return jsonify(commit_infos)
