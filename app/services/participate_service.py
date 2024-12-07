from app import db
from app.models import Group, Participate
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
        new_group_user = Participate(group_id=group_id, user_id=user_id)
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
            db.session.query(Group.group_id, Group.group_name)
            .join(Participate, Participate.group_id == Group.group_id)
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
