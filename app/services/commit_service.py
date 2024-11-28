import re
from datetime import datetime, timezone, timedelta

from sqlalchemy.dialects.mysql import insert
from sqlalchemy import func, extract

from app.constants import ACTIVITY_DAYS, DAYS_IN_WEEK
from app import db
from app.models import User, Commit
from app.constants import COMMIT_TITLE_PATTERN, COMMIT_TITLE_PATTERN_LEVEL, COMMIT_TITLE_PATTERN_TITLE


class CommitService:
    @staticmethod
    def get_user_commits(user_id):
        return User.query.get(user_id)

    @staticmethod
    def get_user_monthly_commit_count(user_id):
        return User.query.get(user_id)

    @staticmethod
    def insert_new_commits(user_id, commit_jsons):
        commit_data_list = []

        for commit_json in commit_jsons:
            # Extract fields from JSON
            commit_date = datetime.strptime(commit_json['author']['date'], "%Y-%m-%dT%H:%M:%SZ")
            commit_url = commit_json['html_url']
            sha = commit_json['sha']
            message = commit_json['message']

            match = re.search(COMMIT_TITLE_PATTERN, message)

            # Process only if match is found
            if match:
                level = match.group(COMMIT_TITLE_PATTERN_LEVEL)
                title = match.group(COMMIT_TITLE_PATTERN_TITLE)
                commit_data_list.append({"commit_date": commit_date, "commit_url": commit_url,
                                         "sha": sha, "level": level, "title": title})

        if commit_data_list:
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
            ])

            # Use MySQL's INSERT IGNORE to skip duplicates
            stmt = stmt.prefix_with("IGNORE")

            db.session.execute(stmt)
            db.session.commit()

            return len(commit_data_list)

        return 0

    @staticmethod
    def get_weekly_info(user_id):
        # Define the range: Last 7 days
        today = datetime.now(timezone.utc).date()
        start_date = today - timedelta(days=ACTIVITY_DAYS)

        # Query for commits in the date range
        results = (
            db.session.query(func.date(Commit.commit_date).label('commit_date'))
            .filter(Commit.user_id == user_id, Commit.commit_date >= start_date)
            .distinct()
            .all()
        )

        # Extract the days with commits
        commit_dates = {result.commit_date for result in results}

        # Check activity for each day in the range
        commit_activity = {
            (start_date + timedelta(days=i)).isoformat(): {
                "committed": (start_date + timedelta(days=i)) in commit_dates,
                "weekday": (start_date + timedelta(days=i)).strftime("%A")
            }
            for i in range(DAYS_IN_WEEK)
        }

        return commit_activity

    @staticmethod
    def count_commits_for_current_month(user_id):
        # Get today's date
        today = datetime.now(timezone.utc)

        # Query to count commits in the current month
        results = (
            db.session.query(
                User.user_id,
                User.github_id,
                func.count(Commit.commit_id).label('commit_count'))
            .join(Commit, User.user_id == Commit.user_id)
            .filter(
                Commit.user_id == user_id,
                extract('month', Commit.commit_date) == today.month,
                extract('year', Commit.commit_date) == today.year
            )
            .group_by(User.user_id, User.github_id)
            .first()
        )

        return results