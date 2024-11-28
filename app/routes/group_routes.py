from pyexpat.errors import messages

from flask import abort, jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from sqlalchemy.exc import IntegrityError

from app import db
from app.constants import DEFAULT_GROUP_MAX_CNT
from app.models import Group
from app.services.commit_service import CommitService
from app.services.group_service import GroupService
from app.services.participate_service import ParticipateService

group_bp = Blueprint('group', __name__)

class GroupQuerySchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to find group")

class GroupRequestSchema(Schema):
    group_name = fields.String(required=True, description="Name of the group")
    group_pw = fields.String(required=True, description="Password of the group")
    member_maxCnt = fields.Integer(description="Max count of members for group", default=5)
    owner = fields.Integer(required=True, description="Max count of members for group", default=5)

class GroupResponseSchema(Schema):
    group_name = fields.String()
    group_pw = fields.String()
    member_maxCnt = fields.Integer()

@group_bp.route('/info', methods=['GET'])
@group_bp.arguments(GroupQuerySchema, location='query')
@group_bp.response(200, GroupResponseSchema)
def get_group_info(query_args):
    """Retrieve group info by ID"""
    user_id = query_args['user_id']
    if not user_id:
        abort(400, description="Missing required query parameter: id")

    group_metadata = ParticipateService.get_group_metadata_by_user_id(user_id)
    if not group_metadata:
        abort(404, "Group not found")

    results = []

    # get all member info from each participating group
    for g_metadata in group_metadata:
        result = {"group_id": g_metadata.group_id, "group_name": g_metadata.group_name, "group_commits": []}
        member_count_commits = {}
        member_ids = ParticipateService.get_member_ids_by_group_id(g_metadata.group_id)
        for user_id in member_ids:
            commit_infos = CommitService.count_commits_for_current_month(user_id)
            member_count_commits[commit_infos.github_id] = {
                "user_id": commit_infos.user_id,
                "commit_count": commit_infos.commit_count
            }
            result["group_commits"].append(tuple(commit_infos))
        results.append(result)

    return jsonify(tuple(results)), 200

    abort(404, description="Group Info not found")

@group_bp.route('/', methods=['POST'])
@group_bp.arguments(GroupRequestSchema, location='json')
@group_bp.response(201, GroupResponseSchema)
def create_group(query_args):
    """Create a new group"""
    # Check if the github_id already exists
    existing_group = Group.query.filter_by(group_name=query_args['group_name']).first()
    if existing_group:
        abort(409, description=f"Group with group_name '{query_args['group_name']}' already exists.")

    try:
        # Check if max_count > 30
        max_mem_count = query_args.get('member_maxCnt', DEFAULT_GROUP_MAX_CNT)
        if max_mem_count > 30:
            abort(409, description=f"Member_maxCnt should be lower or equal to 30")

        # Create new group
        group = GroupService.create_group(query_args['group_name'],
                                          query_args['group_pw'],
                                          max_mem_count,
                                          query_args['owner'])
        ParticipateService.assign_group(group.group_id, query_args['owner'])
        return {
            "group_name": group.group_name,
            "member_maxCnt": group.member_maxCnt,
            "owner": group.owner
        }

    except IntegrityError:
        db.session.rollback()
        abort(500, description="A database error occurred while creating the group.")
