import os

import requests
from flask import abort, jsonify
from flask.cli import load_dotenv
from flask_smorest import Blueprint
from marshmallow import Schema, fields

load_dotenv()

GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN error")

GITHUB_API_URL = "https://api.github.com"

github_bp = Blueprint('github', __name__)

class RepoRequestSchema(Schema):
    github_id = fields.String(required=True, description="GitHub ID of the user")
    repository_name = fields.String(required=True, description="Repository name of the user")

class RepoResponseSchema(Schema):
    name = fields.String(description="Repository name")
    html_url = fields.String(description="Repository URL")
    description = fields.String(description="Repository description")
    language = fields.String(description="Primary programming language")


@github_bp.route('/repo', methods=['GET'])
@github_bp.arguments(RepoRequestSchema, location='query')
@github_bp.response(200, RepoResponseSchema(many=True))
def get_repo(user_data):
    """get GitHub repository of user"""
    owner = user_data['github_id']
    repo = user_data['repository_name']
    if not owner:
        abort(400, description="error: username parameter is required")

    url = f"{GITHUB_API_URL}/repos/{owner}/{repo}"
    headers = {"Authorization": f"token {GITHUB_TOKEN}"}

    try:
        response = requests.get(url, headers=headers)
        if response.status_code == 404:
            abort(404, description=f"GitHub repo {repo} of '{owner}' not found.")
        elif response.status_code != 200:
            abort(500, description="Error occurred while calling the GitHub API.")


        repo = response.json()
        filtered_repo = [
            {
                "name": repo.get("name", "Unknown"),
                "html_url": repo.get("html_url", "Unknown"),
                "description": repo.get("description", "No description"),
            }
        ]

        return jsonify(filtered_repo), 200

    except requests.RequestException as e:
        abort(500, description=f"An error occurred during the request: {str(e)}")

@github_bp.route('/repo/commits', methods=['GET'])
@github_bp.arguments(RepoRequestSchema, location='query')
@github_bp.response(200, RepoResponseSchema(many=True))
def get_commits(user_data):
    """get GitHub Commit history of user"""
    owner = user_data['github_id']
    repo = user_data['repository_name']
    if not owner:
        abort(400, description="error: username parameter is required")

    url = f"{GITHUB_API_URL}/repos/{owner}/{repo}/commits"
    headers = {"Authorization": f"token {GITHUB_TOKEN}"}

    try:
        response = requests.get(url, headers=headers)
        if response.status_code == 404:
            abort(404, description=f"GitHub repo {repo} commits of '{owner}' not found.")
        elif response.status_code != 200:
            abort(500, description="Error occurred while calling the GitHub API.")


        commits = response.json()
        filtered_commits = [
            {
                "sha": commit.get("sha", "Unknown"),
                "author": commit.get("commit", {}).get("author", "No message"),
                "message": commit.get("commit", {}).get("message", "No message"),
                "html_url": commit.get("html_url", "Unknown"),
                "description": commit.get("description", "No description"),
            }
            for commit in commits
        ]

        return jsonify(filtered_commits), 200

    except requests.RequestException as e:
        abort(500, description=f"An error occurred during the request: {str(e)}")