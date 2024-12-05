import requests
from flask_smorest import Blueprint
from marshmallow import Schema, fields

from app.constants import GITHUB_TOKEN, GITHUB_API_URL
from app.error_handler import generate_error
from app.services.github_service import GitHubService

# Ensure GITHUB_TOKEN is set
if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN is missing or invalid.")

# Blueprint
github_bp = Blueprint('Github', __name__)

# ----- Schemas -----
class RepoRequestSchema(Schema):
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class CommitsRequestSchema(Schema):
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class CommitResponseSchema(Schema):
    sha = fields.String(description="The SHA hash of the commit")
    author = fields.Dict(description="Author details of the commit", keys=fields.String(), values=fields.String())
    message = fields.String(description="Commit message")
    html_url = fields.String(description="URL to the commit on GitHub")
    description = fields.String(description="Description of the commit, if available")

# ----- Routes -----

@github_bp.route('/repo', methods=['GET'])
@github_bp.arguments(RepoRequestSchema, location='query')
@github_bp.response(200)
def get_repo(query_args):
    """
    Retrieve details of a GitHub repository.

    Args:
        query_args (dict): Request arguments containing `github_id` and `repository_name`.

    Returns:
        list[dict]: List containing repository details.
    """
    github_id = query_args['github_id']
    repository_name = query_args['repository_name']

    # Construct URL and headers
    url = f"{GITHUB_API_URL}/repos/{github_id}/{repository_name}"
    headers = {"Authorization": f"token {GITHUB_TOKEN}"}

    try:
        # Call GitHub API
        response = requests.get(url, headers=headers)

        # Handle response codes
        if response.status_code == 404:
            generate_error(404, f"GitHub repository '{repository_name}' of '{github_id}' not found.")
        elif response.status_code != 200:
            generate_error(500, "An error occurred while calling the GitHub API.")

        # Parse and filter response
        repo = response.json()
        if repo:
            return True
        return False

    except requests.RequestException as e:
        generate_error(500, f"An error occurred during the request: {str(e)}")

@github_bp.route('/commits', methods=['GET'])
@github_bp.arguments(CommitsRequestSchema, location='query')
@github_bp.response(200, CommitResponseSchema(many=True))
def get_commits(query_args):
    """
    Retrieve commit details from a GitHub repository.

    Args:
        query_args (dict): Request arguments containing `github_id` and `repository_name`.

    Returns:
        list[dict]: List of commits with their details.
    """
    github_id = query_args['github_id']
    repository_name = query_args['repository_name']

    try:
        # Fetch commits using the service
        commits = GitHubService.fetch_commits_from_github(github_id, repository_name)
        return commits
    except Exception as e:
        generate_error(500, f"An error occurred while fetching commits: {str(e)}")
