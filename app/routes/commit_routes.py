from flask import jsonify
from flask_smorest import Blueprint
from marshmallow import Schema, fields

from app.services.commit_service import CommitService
from app.services.github_service import GitHubService

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
    github_id = fields.String(required=True, description="User github_id to fetch")
    repository_name = fields.String(required=True, description="User repository_name to fetch")

class CommitActivityRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to fetch")

@commits_bp.route('', methods=['POST'])
@commits_bp.arguments(StoreCommitsRequestSchema, location='json')
@commits_bp.response(200)
def store_commits(query_arg):
    """Store commits in the database."""
    user_id = query_arg['user_id']
    github_id = query_arg['github_id']
    repository_name = query_arg['repository_name']

    # Call fetch commits and insert new commits
    commits = GitHubService.fetch_commits_from_github(github_id, repository_name)
    CommitService.insert_new_commits(user_id, commits)


    week_activity = CommitService.get_weekly_info(user_id)
    recent_commits = CommitService.get_recent_commits(user_id)
    commit_level_counts = CommitService.get_commit_level_counts(user_id)
    rank_info = CommitService.get_user_rank(user_id)

    result = {
        "week_activity": week_activity,
        "recent_commits": recent_commits,
        "commit_level_counts": commit_level_counts,
        "rank_info": rank_info
    }

    return jsonify(result)

@commits_bp.route('/activity', methods=['GET'])
@commits_bp.arguments(CommitActivityRequestSchema, location='query')
@commits_bp.response(200)
def get_commit_activity(user_data):
    """Get recent 7 days of commit activity"""
    user_id = user_data["user_id"]
    commit_activity = CommitService.get_weekly_info(user_id)

    return jsonify(commit_activity)

@commits_bp.route('', methods=['GET'])
@commits_bp.arguments(CommitActivityRequestSchema, location='query')
@commits_bp.response(200)
def get_recent_commit(user_data):
    """Retrieve recent 10 commit activity of a user."""
    user_id = user_data["user_id"]
    commit_activity = CommitService.get_recent_commits(user_id)

    print(commit_activity)
    return jsonify(commit_activity)

@commits_bp.route('/level', methods=['GET'])
@commits_bp.arguments(CommitActivityRequestSchema, location='query')
@commits_bp.response(200)
def get_commit_level_ratio(user_data):
    """Retrieve recent 10 commit activity of a user."""
    user_id = user_data["user_id"]
    commit_activity = CommitService.get_commit_level_ratio(user_id)

    print(commit_activity)
    return jsonify(commit_activity)
