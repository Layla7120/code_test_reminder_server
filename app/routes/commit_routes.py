from flask import jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields

from app.services.commit_service import CommitService

commits_bp = Blueprint('Commits', __name__)

class CommitSchema(Schema):
    """Schema for individual commit."""
    author = fields.Dict(required=True, description="Author information including date, email, and name.")
    description = fields.String(required=False, description="Commit description.")
    html_url = fields.String(required=True, description="URL of the commit.")
    sha = fields.String(required=True, description="SHA hash of the commit.")
    message = fields.String(required=True, description="Commit message.")

class StoreCommitsRequestSchema(Schema):
    """Schema for storing commits."""
    user_id = fields.Integer(required=True, description="User ID to fetch")
    commits = fields.List(fields.Nested(CommitSchema), required=True, description="List of commits to store.")

class CommitActivityRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to fetch")

@commits_bp.route('', methods=['POST'])
@commits_bp.arguments(StoreCommitsRequestSchema, location='json')
@commits_bp.response(200)
def store_commits(query_arg):
    """Store commits in the database."""
    user_id = query_arg['user_id']
    commits = query_arg['commits']

    # Call the service to insert new commits
    result = CommitService.insert_new_commits(user_id, commits)

    return {
        "message": f"Successfully updated {result} commits.",
        "fetched_commits": result
    }

@commits_bp.route('/activity', methods=['GET'])
@commits_bp.arguments(CommitActivityRequestSchema, location='query')
@commits_bp.response(200)
def get_commit_activity(user_data):
    """Get recent 7 days of commit activity"""
    user_id = user_data["user_id"]
    commit_activity = CommitService.get_weekly_info(user_id)

    return jsonify(commit_activity)
