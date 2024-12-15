import os
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from flask.cli import load_dotenv

load_dotenv()
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
GITHUB_API_URL = "https://api.github.com"
ACTIVITY_DAYS = 6
DAYS_IN_WEEK = 7
DEFAULT_GROUP_MAX_CNT = 5
RANK_PUBLIC_COUNT = 30

TODAY = datetime.now(timezone.utc).astimezone(ZoneInfo("Asia/Seoul"))

COMMIT_TITLE_PATTERN = r"\[(.*?)\] Title: (.*?), Time: (.*?), Memory: (.*?) -"
COMMIT_TITLE_PATTERN_LEVEL = 1
COMMIT_TITLE_PATTERN_TITLE = 2