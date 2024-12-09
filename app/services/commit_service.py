import re
from datetime import datetime, timedelta

from sqlalchemy.dialects.mysql import insert
from sqlalchemy import func, extract, desc, case, Integer, cast

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
        # Get the current date to calculate the current and previous months
        current_year = TODAY.year
        current_month = TODAY.month
        previous_month = current_month - 1 if current_month > 1 else 12
        previous_year = current_year if current_month > 1 else current_year - 1

        subquery = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (
                                        (extract('month', Commit.commit_date) == current_month) &
                                        (extract('year', Commit.commit_date) == current_year),
                                        1
                                    )
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('commit_count'),
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))
                ).label('rank'),
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (
                                        (extract('month', Commit.commit_date) == previous_month) &
                                        (extract('year', Commit.commit_date) == previous_year),
                                        1
                                    )
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('last_month_commit_count')
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # LEFT OUTER JOIN
            .filter(
                (
                        (extract('month', Commit.commit_date) == current_month) &
                        (extract('year', Commit.commit_date) == current_year)
                ) |
                (
                        (extract('month', Commit.commit_date) == previous_month) &
                        (extract('year', Commit.commit_date) == previous_year)
                )
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

        if query_results is None:
            return None

        response_result = [
            {
                "github_id": result.github_id,
                "nick_name": result.nick_name,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "rank": result.rank,
                "last_month_commit_count": result.last_month_commit_count
            }
            for result in query_results
        ]

        return response_result

    @staticmethod
    def get_user_rank(user_id):

        rank_subquery = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label("commit_count"),
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))  # Rank by commit count descending
                ).label('rank'),
                CommitService._calculate_difference_expression(func.count(Commit.commit_id)).label(
                    "difference_from_prev")
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # LEFT OUTER JOIN to include users with no commits
            .filter(
                extract("month", Commit.commit_date) == TODAY.month,
                extract("year", Commit.commit_date) == TODAY.year
            )
            .group_by(User.user_id, User.nick_name, User.github_id)
            .subquery()
        )

        query_results = (
            db.session.query(
                rank_subquery.c.user_id,
                rank_subquery.c.nick_name,
                rank_subquery.c.github_id,
                rank_subquery.c.commit_count,
                rank_subquery.c.rank,
                rank_subquery.c.difference_from_prev
            )
            .filter(rank_subquery.c.user_id == user_id)  # Filter for the specific user
            .first()
        )

        if query_results is None:
            return None

        response_result = {
            "github_id": query_results.github_id,
            "nick_name": query_results.nick_name,
            "user_id": query_results.user_id,
            "commit_count": query_results.commit_count,
            "rank": query_results.rank,
            "difference_from_prev": query_results.difference_from_prev
        }

        return response_result

    @staticmethod
    def get_commit_level_counts(user_id):
        query_results = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                Commit.level,
                func.coalesce(func.count(Commit.commit_id), 0).label("commit_count")
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # LEFT OUTER JOIN to include users with no commits
            .filter(User.user_id == user_id)
            .group_by(User.user_id, User.nick_name, User.github_id, Commit.level)
            .all()
        )

        if query_results is None:
            return None

        format_resultc = [
            {
                "github_id": result.github_id,
                "nick_name": result.nick_name,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "level": result.level
            }
            for result in query_results
        ]

        return format_resultc

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
        print(user_id, start_date, commit_dates)
        # Map commit activity for each day
        commit_activity = [
            {
                "date": (start_date + timedelta(days=i)).isoformat(),
                "committed": (start_date + timedelta(days=i)).date() in commit_dates,
                "weekday": (start_date + timedelta(days=i)).strftime("%A")
            }
            for i in range(DAYS_IN_WEEK)
        ]

        return commit_activity

    @staticmethod
    def count_commits_for_current_month(member_ids):
        """
        Count the number of commits made by a user in the current month.
        """
        # Query to count commits in the current month
        current_year = TODAY.year
        current_month = TODAY.month
        previous_month = current_month - 1 if current_month > 1 else 12
        previous_year = current_year if current_month > 1 else current_year - 1

        query_results = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (
                                        (extract('month', Commit.commit_date) == current_month) &
                                        (extract('year', Commit.commit_date) == current_year),
                                        1
                                    )
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('commit_count'),
                func.rank().over(
                    order_by=desc(func.coalesce(func.count(Commit.commit_id), 0))
                ).label('rank'),
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (
                                        (extract('month', Commit.commit_date) == previous_month) &
                                        (extract('year', Commit.commit_date) == previous_year),
                                        1
                                    )
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('last_month_commit_count')
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)  # Perform a LEFT OUTER JOIN
            .filter(
                User.user_id.in_(member_ids),
                (
                        (extract('month', Commit.commit_date) == current_month) &
                        (extract('year', Commit.commit_date) == current_year)
                ) |
                (
                        (extract('month', Commit.commit_date) == previous_month) &
                        (extract('year', Commit.commit_date) == previous_year)
                )
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
                "last_month_commit_count": result.last_month_commit_count
            }
            for result in query_results
        ]

        return response_result

    @staticmethod
    def get_recent_commits(user_id):
        """
        Retrieve recent 10 commit activity of a user.

        Args:
            user_id (int): ID of the user.

        Returns:
            dict: Dictionary of activity status for the past week.
        """

        # Query for distinct commit dates in the range
        query_results = (
            db.session.query(
                Commit.commit_date,
                Commit.commit_url,
                Commit.level,
                Commit.title
            )
            .filter(Commit.user_id == user_id)
            .order_by(Commit.commit_date.desc())
            .distinct()
            .limit(10)
            .all()
        )

        response_result = [
            {
                "commit_date": result.commit_date,
                "commit_url": result.commit_url,
                "level": result.level,
                "title": result.title
            }
            for result in query_results
        ]

        return response_result
