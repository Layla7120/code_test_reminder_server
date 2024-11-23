import os

import requests
from flask import abort
from flask.cli import load_dotenv
from flask_smorest import Blueprint

load_dotenv()

GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN error")

GITHUB_API_URL = "https://api.github.com"

github_bp = Blueprint('github', __name__)


class GitHubService:
    @staticmethod
    def fetch_commits_from_github(github_id, repository_name):
        """
        Fetch commits from GitHub API for a specific repository.
        """
        if not github_id:
            abort(400, description="error: username parameter is required")

        url = f"{GITHUB_API_URL}/repos/{github_id}/{repository_name}/commits"
        headers = {"Authorization": f"token {GITHUB_TOKEN}"}

        try:
            response = requests.get(url, headers=headers)
            if response.status_code == 404:
                abort(404, description=f"GitHub repo {github_id} commits of '{repository_name}' not found.")
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

            return filtered_commits

        except requests.RequestException as e:
            abort(500, description=f"An error occurred during the request: {str(e)}")
