from flask.cli import load_dotenv
from flask_smorest import Blueprint
from marshmallow import Schema, fields

from app.services.commit_service import CommitService
from app.services.github_service import GitHubService

load_dotenv()

commits_bp = Blueprint('commits', __name__)

class CommitsRequestSchema(Schema):
    user_id = fields.Integer(required=True, description="User ID to fetch")
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")


@commits_bp.route('/', methods=['GET'])
@commits_bp.arguments(CommitsRequestSchema, location='query')
@commits_bp.response(200)
def get_commits(user_data):
    """ Fetch commits from GitHub and store them in the database."""
    github_id = user_data['github_id']
    repository_name = user_data['repository_name']
    user_id = user_data['user_id']

    # Step 1: Fetch commits from GitHub
    commits = GitHubService.fetch_commits_from_github(github_id, repository_name)

    # Step 2: Store commits in the database
    result = CommitService.insert_new_commits(user_id, commits)

    return {
        "message": f"Successfully inserted {result} new commits.",
        "inserted_commits": result
    }
