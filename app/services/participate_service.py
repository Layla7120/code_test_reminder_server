from sqlalchemy.exc import IntegrityError

from app import db, generate_error
from app.constants import TODAY
from app.models import Group, Participate, User
from app.services.group_service import GroupService


class ParticipateService:
    """Service for managing group participation."""

    @staticmethod
    def assign_group(group_id, user_id):
        """
        Assign a user to a group and increment the group counter.

        Args:
            group_id (int): ID of the group.
            user_id (int): ID of the user to assign.

        Returns:
            Participate: The new participation record.
        """
        # Check if user is already in the group
        if ParticipateService.check_if_participating(group_id, user_id):
            raise ValueError(f"User {user_id} is already a member of group {group_id}.")

        # Create and save participation record
        new_group_user = Participate(group_id=group_id, user_id=user_id, created_at = TODAY)
        db.session.add(new_group_user)

        # Increment group member counter
        GroupService.increment_group_counter(group_id)

        db.session.commit()
        return new_group_user

    @staticmethod
    def check_if_participating(group_id, user_id):
        """
        Check if a user is already participating in a group.

        Args:
            group_id (int): ID of the group.
            user_id (int): ID of the user.

        Returns:
            bool: True if user is participating, False otherwise.
        """
        return db.session.query(Participate).filter_by(group_id=group_id, user_id=user_id).first() is not None

    @staticmethod
    def get_group_metadata_by_user_id(user_id):
        """
        Retrieve metadata for groups a user is participating in.

        Args:
            user_id (int): ID of the user.

        Returns:
            list[Group]: List of groups the user is participating in.
        """
        results = (
            db.session.query(
                Group.group_id,
                Group.group_name,
                Group.group_pw,
                Group.member_maxCnt,
                Group.member_counter,
                User.nick_name.label("owner_name"))
            .join(Participate, Participate.group_id == Group.group_id)
            .join(User, User.user_id == Participate.user_id)
            .filter(Participate.user_id == user_id)
            .group_by(Group.group_id, Group.group_name)
            .all()
        )

        return results

    @staticmethod
    def get_member_ids_by_group_id(group_id):
        """
        Retrieve a list of user IDs for members in a group.

        Args:
            group_id (int): ID of the group.

        Returns:
            list[int]: List of user IDs in the group.
        """
        return [
            participant.user_id
            for participant in db.session.query(Participate).filter_by(group_id=group_id).all()
        ]

    @staticmethod
    def returnOldestMember(group_id, excluded_user_id):
        """
        Retrieve the oldest member

        Args:
            group_id (int): ID of the group.
            excluded_user_id (int): this ID will be excluded

        Returns:
            Participate
        """
        return (db.session.query(Participate.group_id, Participate.user_id)
                .filter(Participate.group_id == group_id)
                .filter(Participate.user_id != excluded_user_id)
                .order_by(Participate.created_at.asc())
                .first())

    @staticmethod
    def _handleGroupOwner(group, user_id):
        if group.owner == user_id:
            try:
                new_owner = ParticipateService.returnOldestMember(group_id=group.group_id, excluded_user_id=user_id)
                print("new_owner_id:", new_owner)  # 확인

                if new_owner is not None:
                    group.owner = new_owner.user_id
                    db.session.commit()
                    print(f"Owner updated to {new_owner.user_id}")
                else:
                    generate_error(404, "No valid owner found, cannot update.")
            except IntegrityError as e:
                db.session.rollback()  # 오류 발생 시 롤백
                print(f"IntegrityError handleUserDelete: {e}")
            except Exception as e:
                # 에러 발생 시 롤백
                db.session.rollback()
                return generate_error(500, f"Error occurred deleting participant: {e}")

    @staticmethod
    def handleUserDelete(user_id):
        """
        delete participate

        Args:
            user_id (int): ID of the User.

        Returns:
            Boolean
        """
        try:
            groups = ParticipateService.get_group_metadata_by_user_id(user_id)

            for g in groups:
                # 혼자였다면 삭제
                if g.member_counter == 1:
                    GroupService.delete_group(g.group_id)
                else:
                    # group owner 였다면 새로운 owner 찾아주기
                    ParticipateService._handleGroupOwner(g, user_id)
                    GroupService.decrement_group_counter(g.group_id)

        except IntegrityError as e:
            db.session.rollback()  # 오류 발생 시 롤백
            print(f"IntegrityError: {e}")

        except Exception as e:
            # 에러 발생 시 롤백
            db.session.rollback()
            return generate_error(500, f"Error occurred deleting participant: {e}")

    @staticmethod
    def handleGroupLeave(user_id, group):
        """
        delete participate

        Args:
            user_id (int): ID of the User.
            group : Name of the group
        Returns:
            Boolean
        """
        try:
            # 혼자였다면 삭제
            if group.member_counter == 1:
                GroupService.delete_group(group.group_id)
            else:
                # group owner 였다면 새로운 owner 찾아주기
                ParticipateService._handleGroupOwner(group, user_id)
                GroupService.decrement_group_counter(group.group_id)
            db.session.query(Participate).filter(
                Participate.group_id == group.group_id,
                Participate.user_id == user_id
            ).delete()
            db.session.commit()

            return True
        except IntegrityError as e:
            db.session.rollback()  # 오류 발생 시 롤백
            print(f"IntegrityError: {e}")
            return False
        except Exception as e:
            # 에러 발생 시 롤백
            db.session.rollback()
            return generate_error(500, f"Error occurred deleting participant: {e}")
