from app import db
from app.models import History

class HistoryService:
    @staticmethod
    def create_history(user_id, problem_num, solve_time):
        new_history = History(
            user_id=user_id,
            problem_num=problem_num,
            solve_time=solve_time
        )

        db.session.add(new_history)
        db.session.commit()
        return new_history