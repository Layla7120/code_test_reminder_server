import re
from datetime import datetime, timedelta

from sqlalchemy.dialects.mysql import insert
from sqlalchemy import func, extract, desc

from app.constants import ACTIVITY_DAYS, DAYS_IN_WEEK, TODAY
from app import db
from app.models import User, Commit
from app.constants import (
    COMMIT_TITLE_PATTERN,
    COMMIT_TITLE_PATTERN_LEVEL,
    COMMIT_TITLE_PATTERN_TITLE,
)


class CommitService:
    """Service class for managing commits-related operations."""

    @staticmethod
    def _calculate_difference_expression(commit_count_column):
        """
        Creates a SQLAlchemy expression to calculate the difference between
        the current and previous row's commit count, ensuring non-negative values.
        """
        return func.greatest(
            func.coalesce(
                func.lag(commit_count_column)
                .over(order_by=desc(commit_count_column)),
                0
            ) - func.coalesce(commit_count_column, 0),
            0  # Ensure the result is non-negative
        )

    @staticmethod
    def _get_top_30_commits_query():
        """
        Private method to build the query for top 30 commits ranking.
        Returns a SQLAlchemy query object.
        """
        subquery = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label('commit_count'),  # Use COALESCE to handle NULLs
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))  # Rank by commit count descending
                ).label('rank'),
                CommitService._calculate_difference_expression(func.count(Commit.commit_id)).label("difference_from_prev"),
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # LEFT OUTER JOIN
            .filter(
                extract('month', Commit.commit_date) == TODAY.month,
                extract('year', Commit.commit_date) == TODAY.year
            )
            .group_by(User.user_id, User.nick_name, User.github_id)
            .subquery()
        )

        top_30_query = (
            db.session.query(subquery)
            .order_by(subquery.c.rank)
            .limit(30)
        )

        return top_30_query

    # ----- Rank View - Total Commit Retrieval -----
    @staticmethod
    def get_info_for_rank_view():

        query_results = CommitService._get_top_30_commits_query()

        response_result = [
            {
                "github_id": result.github_id,
                "nick_name": result.nick_name,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "rank": result.rank,
                "difference_from_prev": result.difference_from_prev
            }
            for result in query_results
        ]

        return response_result

    @staticmethod
    def get_user_rank(user_id):

        query_results = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label('commit_count'),  # Use COALESCE to handle NULLs
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))  # Rank by commit count descending
                ).label('rank'),
                CommitService._calculate_difference_expression(func.count(Commit.commit_id)).label("difference_from_prev"),
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # LEFT OUTER JOIN
            .filter(
                User.user_id == user_id,
                extract('month', Commit.commit_date) == TODAY.month,
                extract('year', Commit.commit_date) == TODAY.year
            )
            .group_by(User.user_id, User.nick_name, User.github_id)
            .first()
        )

        response_result = {
            "github_id": query_results.github_id,
            "nick_name": query_results.nick_name,
            "user_id": query_results.user_id,
            "commit_count": query_results.commit_count,
            "rank": query_results.rank,
            "difference_from_prev": query_results.difference_from_prev
        }

        return response_result

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
        start_date = TODAY - timedelta(days=ACTIVITY_DAYS)

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
        # Query to count commits in the current month
        query_results = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label('commit_count'),  # Use COALESCE to handle NULLs
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))  # Rank by commit count descending
                ).label('rank'),
                CommitService._calculate_difference_expression(func.count(Commit.commit_id)).label("difference_from_prev"),
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # Perform a LEFT OUTER JOIN
            .filter(
                User.user_id.in_(member_ids),  # Filter by member IDs
                extract('month', Commit.commit_date) == TODAY.month,
                extract('year', Commit.commit_date) == TODAY.year
            )
            .group_by(User.user_id, User.github_id)  # Group by user fields
            .all()
        )

        response_result = [
            {
                "github_id": result.github_id,
                "nick_name": result.nick_name,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "rank": result.rank,
                "difference_from_prev": result.difference_from_prev
            }
            for result in query_results
        ]

        return response_result
