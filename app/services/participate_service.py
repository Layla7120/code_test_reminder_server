from app import db
from app.models import Group, Participate


class ParticipateService:
    @staticmethod
    def assign_group(group_id, user_id):
        new_group_user = Participate(group_id=group_id, user_id=user_id)
        db.session.add(new_group_user)
        db.session.commit()
        return new_group_user

    @staticmethod
    def get_group_metadata_by_user_id(user_id):
        results = (
            db.session.query(Group.group_id, Group.group_name)
            .join(Participate, Participate.group_id == Group.group_id)
            .filter(Participate.user_id==user_id)
            .group_by(Group.group_id,Group.group_name)
            .all()
        )
        return results

    @staticmethod
    def get_member_ids_by_group_id(group_id):
        return [participant.user_id for participant in Participate.query.filter_by(group_id=group_id).all()]

