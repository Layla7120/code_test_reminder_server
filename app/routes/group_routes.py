from flask import abort, jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from sqlalchemy.exc import IntegrityError

from app import db
from app.constants import DEFAULT_GROUP_MAX_CNT
from app.models import Group, User
from app.services.commit_service import CommitService
from app.services.group_service import GroupService
from app.services.participate_service import ParticipateService
from app.services.user_service import UserService

group_bp = Blueprint('group', __name__)

class GroupQuerySchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to find group")

class CreateGroupRequestSchema(Schema):
    group_name = fields.String(required=True, description="Name of the group")
    group_pw = fields.String(required=True, description="Password of the group")
    member_maxCnt = fields.Integer(description="Max count of members for group", load_default=5)
    owner_user_id = fields.Integer(required=True, description="Max count of members for group", dump_default=5)

class AddMemberRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to add")
    group_name = fields.String(required=True, description="Name of the group")

class GroupResponseSchema(Schema):
    group_name = fields.String()
    group_pw = fields.String()
    member_maxCnt = fields.Integer()


@group_bp.route('/info', methods=['GET'])
@group_bp.arguments(GroupQuerySchema, location='query')
@group_bp.response(200, GroupResponseSchema)
def get_group_info(query_args):
    """Retrieve group info by ID"""
    user_id = query_args.get('user_id')  # Safer way to get 'user_id'

    if not user_id:
        abort(400, description="Missing required query parameter: user_id")

    group_metadata = ParticipateService.get_group_metadata_by_user_id(user_id)
    if not group_metadata:
        abort(404, description="Group not found")

    results = []

    for g_metadata in group_metadata:
        result = {
            "group_id": g_metadata.group_id,
            "group_name": g_metadata.group_name,
            "group_commits": []
        }

        # Initialize dictionary to hold commit info by github_id
        member_count_commits = {}

        # Get member IDs by group_id
        member_ids = ParticipateService.get_member_ids_by_group_id(g_metadata.group_id)
        for member_user_id in member_ids:
            commit_infos = CommitService.count_commits_for_current_month(member_user_id)

            member_count_commits[commit_infos.github_id] = {
                "user_id": commit_infos.user_id,
                "commit_count": commit_infos.commit_count
            }

            # Append commit info to the group's result
            result["group_commits"].append({
                "github_id": commit_infos.github_id,
                "user_id": commit_infos.user_id,
                "commit_count": commit_infos.commit_count
            })

        results.append(result)

    return jsonify(results), 200

@group_bp.route('/', methods=['POST'])
@group_bp.arguments(CreateGroupRequestSchema, location='json')
@group_bp.response(201, GroupResponseSchema)
def create_group(query_args):
    """Create a new group"""
    # Check if the group_name already exists
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
                                          query_args['owner_user_id'])
        ParticipateService.assign_group(group.group_id, query_args['owner_user_id'])
        return {
            "group_name": group.group_name,
            "member_maxCnt": group.member_maxCnt,
            "owner_user_id": group.owner
        }

    except IntegrityError:
        db.session.rollback()
        abort(500, description="A database error occurred while creating the group.")


@group_bp.route('/member', methods=['POST'])
@group_bp.arguments(AddMemberRequestSchema, location='json')
@group_bp.response(201)
def add_member(query_args):
    """add member to a group"""
    # Check if the user_id already exists
    user_id = UserService.get_user_by_user_id(query_args['user_id']).user_id
    if not user_id:
        abort(409, description=f"user id doesn't exist.")

    try:
        group_id = GroupService.get_group_id(query_args['group_name'])
        ParticipateService.assign_group(group_id, user_id)
        return {
            "group_name": query_args['group_name'],
            "user_id": query_args['user_id']
        }
    except IntegrityError:
        db.session.rollback()
        abort(500, description="A database error occurred while adding member to the group.")