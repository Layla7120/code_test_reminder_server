import re
from datetime import datetime

from sqlalchemy.dialects.mysql import insert

from app import db
from app.models import User, Commit


class CommitService:
    @staticmethod
    def get_user_commits(user_id):
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
            pattern = r"\[(.*?)\] Title: (.*?), Time: (.*?), Memory: (.*?) -"

            match = re.search(pattern, message)

            # Process only if match is found
            if match:
                level = match.group(1)
                title = match.group(2)
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
