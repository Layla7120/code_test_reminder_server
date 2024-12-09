from flask import jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields
from marshmallow.validate import Range

from app.constants import DEFAULT_GROUP_MAX_CNT
from app.error_handler import generate_error, handle_errors
from app.services.commit_service import CommitService
from app.services.group_service import GroupService
from app.services.participate_service import ParticipateService
from app.services.user_service import UserService
from app.extensions import bcrypt

group_bp = Blueprint('Group', __name__)

# ----- SCHEMAS -----
class GroupQuerySchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to find group")

class CreateGroupRequestSchema(Schema):
    group_name = fields.String(required=True, description="Name of the group")
    group_pw = fields.String(required=True, description="Password of the group")
    member_maxCnt = fields.Integer(
        description="Max count of members for group",
        load_default=5,
        validate=Range(min=1, max=30)
    )
    owner_user_id = fields.Integer(required=True, description="Owner User ID", dump_default=5)

class AddMemberRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to add")
    group_name = fields.String(required=True, description="Group ID to add")
    group_pw = fields.String(required=True, description="Group password entered by the user")

class SearchRequestSchema(Schema):
    group_name = fields.String(required=True, description="Name of the group")

class GroupResponseSchema(Schema):
    group_id = fields.String()
    group_name = fields.String()
    group_pw = fields.String()
    member_maxCnt = fields.Integer(description="Max Count of members per group")

# ----- ROUTES -----

@group_bp.route('/info', methods=['GET'])
@group_bp.arguments(GroupQuerySchema, location='query')
@group_bp.response(200, GroupResponseSchema)
@handle_errors
def get_group_info(query_args):
    """Retrieve group info by user ID."""
    user_id = query_args['user_id']

    group_metadata = ParticipateService.get_group_metadata_by_user_id(user_id)
    if not group_metadata:
        return generate_error(404, "Group not found")

    results = []
    for g_metadata in group_metadata:
        group_data = {
            "group_id": g_metadata.group_id,
            "group_name": g_metadata.group_name,
            "group_commits": []
        }

        member_ids = ParticipateService.get_member_ids_by_group_id(g_metadata.group_id)
        commit_infos = CommitService.count_commits_for_current_month(member_ids)
        group_data["group_commits"] = (commit_infos)
        results.append(group_data)
    print(results)
    return jsonify(results)

@group_bp.route('', methods=['POST'])
@group_bp.arguments(CreateGroupRequestSchema, location='json')
@group_bp.response(201, GroupResponseSchema)
@handle_errors
def create_group(query_args):
    """Create a new group."""
    if GroupService.get_group_by_name(query_args['group_name']):
        return generate_error(409, f"Group with name '{query_args['group_name']}' already exists.")

    owner = UserService.get_user_by_user_id(query_args['owner_user_id'])
    if not owner:
        return generate_error(404, f"User ID '{query_args['owner_user_id']}' does not exist.")

    max_mem_count = query_args.get('member_maxCnt', DEFAULT_GROUP_MAX_CNT)
    if max_mem_count > 30:
        return generate_error(409, "Member_maxCnt should be lower or equal to 30.")

    crypt_pw = bcrypt.generate_password_hash(query_args['group_pw']).decode('utf-8')
    group = GroupService.create_group(query_args['group_name'], crypt_pw, max_mem_count, owner.user_id)
    ParticipateService.assign_group(group.group_id, query_args['owner_user_id'])

    return {
        "group_id": group.group_id,
        "group_name": group.group_name,
        "member_maxCnt": group.member_maxCnt,
        "owner_user_id": group.owner
    }

@group_bp.route('/member', methods=['POST'])
@group_bp.arguments(AddMemberRequestSchema, location='json')
@group_bp.response(201)
@handle_errors
def add_member(query_args):
    """Add a member to a group."""
    print(query_args)
    user = UserService.get_user_by_user_id(query_args['user_id'])
    if not user:
        return generate_error(404, f"User ID '{query_args['user_id']}' does not exist.")

    group = GroupService.get_group_by_name(query_args['group_name'])
    if not group:
        return generate_error(404, f"Group ID '{query_args['group_name']}' does not exist.")

    GroupService.check_group_limit(group)
    if not GroupService.validate_password(group, query_args['group_pw']):
        return generate_error(403, "Password is incorrect.")

    ParticipateService.assign_group(group.group_id, user.user_id)

    return {
        "group_id": group.group_id,
        "group_name": group.group_name,
        "user_id": query_args['user_id']
    }

@group_bp.route('/search', methods=['GET'])
@group_bp.arguments(SearchRequestSchema, location='query')
@group_bp.response(200)
@handle_errors
def search_group(query_args):
    """Search for groups by name."""
    group_name = query_args['group_name']
    group_list = GroupService.search_group_name_starts_with(group_name)
    print(group_list)
    if not group_list:
        return generate_error(404, "Group not found")
    return jsonify(group_list)
