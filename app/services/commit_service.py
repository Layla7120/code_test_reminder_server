import re
from datetime import datetime, timezone, timedelta

from sqlalchemy.dialects.mysql import insert
from sqlalchemy import func, extract, desc

from app.constants import ACTIVITY_DAYS, DAYS_IN_WEEK
from app import db
from app.models import User, Commit
from app.constants import (
    COMMIT_TITLE_PATTERN,
    COMMIT_TITLE_PATTERN_LEVEL,
    COMMIT_TITLE_PATTERN_TITLE,
)


class CommitService:
    """Service class for managing commits-related operations."""

    # ----- User Commit Retrieval -----
    @staticmethod
    def get_user_commits(user_id):
        """Retrieve commits for a specific user."""
        return User.query.get(user_id)

    @staticmethod
    def get_user_monthly_commit_count(user_id):
        """Retrieve monthly commit count for a specific user."""
        return User.query.get(user_id)

    # ----- Commit Insertion -----
    @staticmethod
    def insert_new_commits(user_id, commit_jsons):
        """
        Insert new commits into the database, avoiding duplicates.

        Args:
            user_id (int): ID of the user making the commits.
            commit_jsons (list): List of JSON objects containing commit data.

        Returns:
            int: The number of new commits inserted.
        """
        commit_data_list = []

        for commit_json in commit_jsons:
            # Extract fields from JSON
            commit_date = datetime.strptime(commit_json['author']['date'], "%Y-%m-%dT%H:%M:%SZ")
            commit_url = commit_json['html_url']
            sha = commit_json['sha']
            message = commit_json['message']

            # Match commit message against a pattern
            match = re.search(COMMIT_TITLE_PATTERN, message)

            if match:
                # Extract level and title from the message
                level = match.group(COMMIT_TITLE_PATTERN_LEVEL)
                title = match.group(COMMIT_TITLE_PATTERN_TITLE)
                commit_data_list.append({
                    "commit_date": commit_date,
                    "commit_url": commit_url,
                    "sha": sha,
                    "level": level,
                    "title": title
                })

        if commit_data_list:
            # Insert new commits while ignoring duplicates
            stmt = insert(Commit).values([
                {
                    "user_id": user_id,
                    "commit_date": commit_data["commit_date"],
                    "commit_url": commit_data["commit_url"],
                    "title": commit_data["title"],
                    "level": commit_data["level"],
                    "sha": commit_data["sha"]
                }
                for commit_data in commit_data_list
            ]).prefix_with("IGNORE")  # Use MySQL's INSERT IGNORE

            db.session.execute(stmt)
            db.session.commit()

            return len(commit_data_list)

        return 0

    # ----- Commit Activity Tracking -----
    @staticmethod
    def get_weekly_info(user_id):
        """
        Retrieve weekly commit activity for a user.

        Args:
            user_id (int): ID of the user.

        Returns:
            dict: Dictionary of activity status for the past week.
        """
        # Define the date range
        today = datetime.now(timezone.utc).date()
        start_date = today - timedelta(days=ACTIVITY_DAYS)

        # Query for distinct commit dates in the range
        results = (
            db.session.query(func.date(Commit.commit_date).label('commit_date'))
            .filter(Commit.user_id == user_id, Commit.commit_date >= start_date)
            .distinct()
            .all()
        )

        # Extract the days with commits
        commit_dates = {result.commit_date for result in results}

        # Map commit activity for each day
        commit_activity = {
            (start_date + timedelta(days=i)).isoformat(): {
                "committed": (start_date + timedelta(days=i)) in commit_dates,
                "weekday": (start_date + timedelta(days=i)).strftime("%A")
            }
            for i in range(DAYS_IN_WEEK)
        }

        return commit_activity

    @staticmethod
    def count_commits_for_current_month(member_ids):
        """
        Count the number of commits made by a user in the current month.
        """
        # Get today's date
        today = datetime.now(timezone.utc)

        # Query to count commits in the current month
        query_results = (
            db.session.query(
                User.user_id,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label('commit_count'),  # Use COALESCE to handle NULLs
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))  # Rank by commit count descending
                ).label('rank'),
                ( func.lag(func.count(Commit.commit_id)).over(order_by=desc(func.count(Commit.commit_id)))
                  - func.count(Commit.commit_id)
                ).label("difference_from_prev"),
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # Perform a LEFT OUTER JOIN
            .filter(
                User.user_id.in_(member_ids),  # Filter by member IDs
                extract('month', Commit.commit_date) == today.month,
                extract('year', Commit.commit_date) == today.year
            )
            .group_by(User.user_id, User.github_id)  # Group by user fields
            .all()
        )

        response_result = [
            {
                "github_id": result.github_id,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "rank": result.rank,
                "difference_from_prev": result.difference_from_prev
            }
            for result in query_results
        ]

        return response_result
