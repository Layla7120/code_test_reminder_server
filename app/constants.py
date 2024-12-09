import os
from datetime import datetime, timezone, timedelta

from flask.cli import load_dotenv

load_dotenv()
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
GITHUB_API_URL = "https://api.github.com"
ACTIVITY_DAYS = 6
DAYS_IN_WEEK = 7
DEFAULT_GROUP_MAX_CNT = 5
RANK_PUBLIC_COUNT = 30

# TODO: 데이터가 없어서 TODAY 날짜를 과거로 돌려놨음
TODAY = datetime.now(timezone.utc) - timedelta(days=15)

COMMIT_TITLE_PATTERN = r"\[(.*?)\] Title: (.*?), Time: (.*?), Memory: (.*?) -"
COMMIT_TITLE_PATTERN_LEVEL = 1
COMMIT_TITLE_PATTERN_TITLE = 2