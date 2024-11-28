from app import db
from app.models import Group, Participate


class GroupService:
    @staticmethod
    def get_group_info(group_id):
        return Group.query.get(group_id)

    @staticmethod
    def get_group_id(group_name):
        return Group.query.filter_by(group_name=group_name).first().group_id

    @staticmethod
    def create_group(group_name, group_pw, member_max_cnt, group_owner_id):
        new_group = Group(group_name=group_name, group_pw=group_pw, member_maxCnt=member_max_cnt, owner=group_owner_id)
        db.session.add(new_group)
        db.session.commit()
        return new_group


    # @staticmethod
    # def delete_user(user_id):
    #     user = User.query.get(user_id)
    #     if user:
    #         db.session.delete(user)
    #         db.session.commit()
