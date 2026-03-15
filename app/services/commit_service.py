import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy.dialects.mysql import insert
from sqlalchemy import func, desc, case, Integer, cast, distinct

from app.constants import ACTIVITY_DAYS, DAYS_IN_WEEK, TODAY
from app import db
from app.extensions import cache
from app.models import User, Commit
from app.constants import (
    COMMIT_TITLE_PATTERN,
    COMMIT_TITLE_PATTERN_LEVEL,
    COMMIT_TITLE_PATTERN_TITLE,
)

KST = ZoneInfo("Asia/Seoul")


def _month_bounds(year: int, month: int) -> tuple[datetime, datetime]:
    """
    지정 연/월의 시작(inclusive)과 다음달 시작(exclusive) 반환.
    범위 비교 쿼리로 commit_date 인덱스를 활용하기 위한 헬퍼.

    extract(month/year, ...) 대신 아래처럼 사용:
        Commit.commit_date >= start,
        Commit.commit_date <  end
    """
    start = datetime(year, month, 1, tzinfo=KST)
    if month == 12:
        end = datetime(year + 1, 1, 1, tzinfo=KST)
    else:
        end = datetime(year, month + 1, 1, tzinfo=KST)
    return start, end


def _prev_year_month(year: int, month: int) -> tuple[int, int]:
    """이전 달의 (year, month) 반환. 1월이면 전년도 12월."""
    if month == 1:
        return year - 1, 12
    return year, month - 1


class CommitService:
    """Service class for managing commits-related operations."""

    @staticmethod
    def _get_top_30_commits_query():
        """
        Private method to build the query for top 30 commits ranking.
        Returns a SQLAlchemy query object.
        """
        current_year = TODAY.year
        current_month = TODAY.month
        prev_year, prev_month = _prev_year_month(current_year, current_month)

        cur_start, cur_end = _month_bounds(current_year, current_month)
        prev_start, prev_end = _month_bounds(prev_year, prev_month)

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
                                    (Commit.commit_date >= cur_start) &
                                    (Commit.commit_date < cur_end),
                                    1
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('commit_count'),
                func.dense_rank().over(
                    order_by=desc(
                        cast(
                            func.coalesce(
                                func.sum(
                                    case(
                                        (
                                            (Commit.commit_date >= cur_start) &
                                            (Commit.commit_date < cur_end),
                                            1
                                        ),
                                        else_=0
                                    )
                                ), 0
                            ), Integer
                        )
                    )
                ).label('rank'),
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (Commit.commit_date >= prev_start) &
                                    (Commit.commit_date < prev_end),
                                    1
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('last_month_commit_count')
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(
                (Commit.commit_date >= prev_start) &
                (Commit.commit_date < cur_end)
            )
            .group_by(User.user_id, User.nick_name, User.github_id)
            .subquery()
        )

        return (
            db.session.query(subquery)
            .order_by(subquery.c.rank)
            .limit(30)
        )

    # ----- Rank View - Total Commit Retrieval -----
    @staticmethod
    @cache.cached(timeout=60, key_prefix="rank_top30")
    def get_info_for_rank_view():
        query_results = CommitService._get_top_30_commits_query()

        if query_results is None:
            return None

        return [
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

    @staticmethod
    def get_user_rank(user_id):
        current_year = TODAY.year
        current_month = TODAY.month

        cur_start, cur_end = _month_bounds(current_year, current_month)

        rank_subquery = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.coalesce(func.count(Commit.commit_id), 0).label("commit_count"),
                func.dense_rank().over(
                    order_by=desc(
                        cast(
                            func.coalesce(
                                func.sum(
                                    case(
                                        (
                                            (Commit.commit_date >= cur_start) &
                                            (Commit.commit_date < cur_end),
                                            1
                                        ),
                                        else_=0
                                    )
                                ), 0
                            ), Integer
                        )
                    )
                ).label('rank')
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(
                (Commit.commit_date >= cur_start) &
                (Commit.commit_date < cur_end)
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
                rank_subquery.c.rank
            )
            .filter(rank_subquery.c.user_id == user_id)
            .first()
        )

        rank_info = (
            db.session.query(
                distinct(func.coalesce(func.count(Commit.commit_id), 0)).label("commit_count"),
                func.dense_rank().over(
                    order_by=desc(
                        cast(
                            func.coalesce(
                                func.sum(
                                    case(
                                        (
                                            (Commit.commit_date >= cur_start) &
                                            (Commit.commit_date < cur_end),
                                            1
                                        ),
                                        else_=0
                                    )
                                ), 0
                            ), Integer
                        )
                    )
                ).label('rank')
            )
            .filter(
                (Commit.commit_date >= cur_start) &
                (Commit.commit_date < cur_end)
            )
            .group_by(Commit.user_id)
            .all()
        )
        rank_to_commit = {rank: commit_count for commit_count, rank in rank_info}

        if query_results is None:
            user = User.query.get(user_id)
            return {
                "github_id": user.github_id,
                "nick_name": user.nick_name,
                "user_id": user.user_id,
                "commit_count": 0,
                "rank": 0,
                "difference_from_prev": -1
            }

        diff = 0
        if rank_to_commit.get(query_results.rank - 1):
            diff = rank_to_commit.get(query_results.rank - 1) - query_results.commit_count

        return {
            "github_id": query_results.github_id,
            "nick_name": query_results.nick_name,
            "user_id": query_results.user_id,
            "commit_count": query_results.commit_count,
            "rank": query_results.rank,
            "difference_from_prev": diff
        }

    @staticmethod
    def get_commit_level_counts(user_id):
        query_results = (
            db.session.query(
                User.user_id,
                User.nick_name,
                User.github_id,
                func.substring_index(Commit.level, ' ', 1).label("level"),
                func.coalesce(func.count(Commit.commit_id), 0).label("commit_count")
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(User.user_id == user_id)
            .group_by(User.user_id, User.nick_name, User.github_id, func.substring_index(Commit.level, ' ', 1))
            .all()
        )

        if query_results is None:
            return None

        return [
            {
                "github_id": result.github_id,
                "nick_name": result.nick_name,
                "user_id": result.user_id,
                "commit_count": result.commit_count,
                "level": result.level or "unrated"
            }
            for result in query_results
        ]

    # ----- Commit Insertion -----
    @staticmethod
    def insert_new_commits(user_id, commit_jsons):
        commit_data_list = []

        print(commit_jsons)
        for commit_json in commit_jsons:
            commit_date = datetime.strptime(commit_json['author']['date'], "%Y-%m-%dT%H:%M:%SZ")
            commit_url = commit_json['html_url']
            sha = commit_json['sha']
            message = commit_json['message']

            match = re.search(COMMIT_TITLE_PATTERN, message)

            if match:
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
            print(commit_data_list)
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
            ]).on_duplicate_key_update(user_id=user_id)

            db.session.execute(stmt)
            db.session.commit()

            return len(commit_data_list)

        return 0

    # ----- Commit Activity Tracking -----
    @staticmethod
    def get_weekly_info(user_id):
        start_date = TODAY - timedelta(days=ACTIVITY_DAYS)

        results = (
            db.session.query(func.date(Commit.commit_date).label('commit_date'))
            .filter(Commit.user_id == user_id, Commit.commit_date >= start_date)
            .distinct()
            .all()
        )

        commit_dates = {result.commit_date for result in results}

        return [
            {
                "date": (start_date + timedelta(days=i)).isoformat(),
                "committed": (start_date + timedelta(days=i)).date() in commit_dates,
                "weekday": (start_date + timedelta(days=i)).strftime("%A")
            }
            for i in range(DAYS_IN_WEEK)
        ]

    @staticmethod
    def count_commits_for_current_month(member_ids):
        current_year = TODAY.year
        current_month = TODAY.month
        prev_year, prev_month = _prev_year_month(current_year, current_month)

        cur_start, cur_end = _month_bounds(current_year, current_month)
        prev_start, prev_end = _month_bounds(prev_year, prev_month)

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
                                    (Commit.commit_date >= cur_start) &
                                    (Commit.commit_date < cur_end),
                                    1
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('commit_count'),
                func.dense_rank().over(
                    order_by=desc(
                        cast(
                            func.coalesce(
                                func.sum(
                                    case(
                                        (
                                            (Commit.commit_date >= cur_start) &
                                            (Commit.commit_date < cur_end),
                                            1
                                        ),
                                        else_=0
                                    )
                                ), 0
                            ), Integer
                        )
                    )
                ).label('rank'),
                cast(
                    func.coalesce(
                        func.sum(
                            case(
                                (
                                    (Commit.commit_date >= prev_start) &
                                    (Commit.commit_date < prev_end),
                                    1
                                ),
                                else_=0
                            )
                        ), 0
                    ), Integer
                ).label('last_month_commit_count')
            )
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(
                User.user_id.in_(member_ids),
                (Commit.commit_date >= prev_start) &
                (Commit.commit_date < cur_end)
            )
            .group_by(User.user_id, User.github_id)
            .all()
        )

        return [
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

    @staticmethod
    def get_all_commits(user_id):
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
            .all()
        )

        return [
            {
                "commit_date": result.commit_date,
                "commit_url": result.commit_url,
                "level": result.level,
                "title": result.title
            }
            for result in query_results
        ]

    @staticmethod
    def get_month_commit_grass(user_id):
        current_year = TODAY.year
        current_month = TODAY.month
        prev_year, prev_month = _prev_year_month(current_year, current_month)

        cur_start, cur_end = _month_bounds(current_year, current_month)
        prev_start, prev_end = _month_bounds(prev_year, prev_month)

        previous_month_query = (
            db.session.query(
                func.date(Commit.commit_date).label('commit_date'),
                func.count(Commit.commit_date).label('commit_count')
            )
            .select_from(User)
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(
                User.user_id == user_id,
                Commit.commit_date >= prev_start,
                Commit.commit_date < prev_end
            )
            .group_by(func.date(Commit.commit_date))
            .order_by(func.date(Commit.commit_date))
            .all()
        )

        current_month_query = (
            db.session.query(
                func.date(Commit.commit_date).label('commit_date'),
                func.count(Commit.commit_date).label('commit_count')
            )
            .select_from(User)
            .outerjoin(Commit, User.user_id == Commit.user_id)
            .filter(
                User.user_id == user_id,
                Commit.commit_date >= cur_start,
                Commit.commit_date < cur_end
            )
            .group_by(func.date(Commit.commit_date))
            .order_by(func.date(Commit.commit_date))
            .all()
        )

        return {
            "previous_month": [
                {"commit_date": i.commit_date, "commit_count": i.commit_count}
                for i in previous_month_query
            ],
            "current_month": [
                {"commit_date": i.commit_date, "commit_count": i.commit_count}
                for i in current_month_query
            ]
        }

    @staticmethod
    def delete_commit(user_id):
        try:
            rows_deleted = db.session.query(Commit).filter(Commit.user_id == user_id).delete()
            db.session.commit()
            return rows_deleted > 0
        except Exception as e:
            db.session.rollback()
            print(f"Error deleting commit with user_id {user_id}: {e}")
            return False
