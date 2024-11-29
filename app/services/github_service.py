import os
import requests
from flask.cli import load_dotenv
from flask_smorest import Blueprint
from app.error_handler import generate_error

load_dotenv()

# Constants
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN environment variable is missing or invalid.")

GITHUB_API_URL = "https://api.github.com"

# Blueprint
github_bp = Blueprint('github', __name__)

class GitHubService:
    """Service for interacting with the GitHub API."""

    @staticmethod
    def fetch_commits_from_github(github_id, repository_name):
        """
        Fetch commits from GitHub API for a specific repository.

        Args:
            github_id (str): GitHub username or organization name.
            repository_name (str): Name of the repository.

        Returns:
            list[dict]: A list of commits with their details.

        Raises:
            generate_error: Returns appropriate HTTP status code and message in case of errors.
        """
        # Validate input
        if not github_id:
            generate_error(400, "Error: 'github_id' parameter is required.")
        if not repository_name:
            generate_error(400, "Error: 'repository_name' parameter is required.")

        # Construct URL and headers
        url = f"{GITHUB_API_URL}/repos/{github_id}/{repository_name}/commits"
        headers = {"Authorization": f"token {GITHUB_TOKEN}"}

        try:
            # Make the API call
            response = requests.get(url, headers=headers)

            # Handle API errors
            if response.status_code == 404:
                generate_error(404, f"GitHub repository '{github_id}/{repository_name}' not found.")
            elif response.status_code == 401:
                generate_error(401, "Unauthorized: Invalid or missing GitHub token.")
            elif response.status_code != 200:
                generate_error(500, "An unexpected error occurred while calling the GitHub API.")

            # Process and filter commits
            return GitHubService._filter_commits(response.json())

        except requests.RequestException as e:
            generate_error(500, f"An error occurred during the GitHub API request: {str(e)}")

    @staticmethod
    def _filter_commits(commits):
        """
        Filter and structure commit data.

        Args:
            commits (list[dict]): Raw commits data from the GitHub API.

        Returns:
            list[dict]: Filtered and structured commit details.
        """
        return [
            {
                "sha": commit.get("sha", "Unknown"),
                "author": commit.get("commit", {}).get("author", {}),
                "message": commit.get("commit", {}).get("message", "No message"),
                "html_url": commit.get("html_url", "Unknown"),
                "description": commit.get("description", "No description"),
            }
            for commit in commits
        ]
