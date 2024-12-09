from operator import truediv

from app import db, bcrypt
from app.error_handler import generate_error
from app.models import Group


class GroupService:
    """Service class for managing Group-related operations."""

    # ----- Group Retrieval Methods -----
    @staticmethod
    def get_group_by_name(group_name):
        """
        Retrieve group details by group name.

        Args:
            group_name (str): Name of the group.

        Returns:
            Group: Group object if found, else None.
        """
        return Group.query.filter_by(group_name=group_name).first()

    @staticmethod
    def get_group_by_id(group_id):
        """
        Retrieve group details by group ID.

        Args:
            group_id (int): ID of the group.

        Returns:
            Group: Group object if found, else None.
        """
        return Group.query.get(group_id)

    @staticmethod
    def search_group_name_starts_with(prefix):
        """
        Search for groups whose names start with a given prefix.

        Args:
            prefix (str): Prefix for the group name.

        Returns:
            list[dict]: List of groups with group details.
        """
        groups = Group.query.filter(Group.group_name.startswith(prefix)).all()
        return [
            {
                "group_id": group.group_id,
                "group_name": group.group_name,
                "group_pw": group.group_pw,
                "member_maxCnt": group.member_maxCnt,
                "member_counter": group.member_counter
            }
            for group in groups
        ]

    # ----- Group Creation and Deletion -----
    @staticmethod
    def create_group(group_name, group_pw, member_max_cnt, group_owner_id):
        """
        Create a new group.

        Args:
            group_name (str): Name of the group.
            group_pw (str): Encrypted password of the group.
            member_max_cnt (int): Maximum number of members allowed in the group.
            group_owner_id (int): ID of the group owner.

        Returns:
            Group: Newly created group.
        """
        new_group = Group(
            group_name=group_name,
            group_pw=group_pw,
            member_maxCnt=member_max_cnt,
            owner=group_owner_id
        )
        db.session.add(new_group)
        db.session.commit()
        return new_group

    # ----- Group Counter Management -----
    @staticmethod
    def increment_group_counter(group_id):
        """
        Increment the member counter for a group.

        Args:
            group_id (int): ID of the group.

        Returns:
            int: Updated member counter value.

        Raises:
            ValueError: If the group does not exist.
        """
        group = Group.query.get(group_id)
        if not group:
            return generate_error(404, f"Group with ID {group_id} not found.")
        group.member_counter += 1
        db.session.commit()
        return group.member_counter

    @staticmethod
    def decrement_group_counter(group_id):
        """
        Decrement the member counter for a group.

        Args:
            group_id (int): ID of the group.

        Returns:
            int: Updated member counter value.

        Raises:
            ValueError: If the group does not exist.
        """
        group = Group.query.get(group_id)
        if not group:
            return generate_error(404, f"Group with ID {group_id} not found.")
        group.member_counter -= 1
        db.session.commit()
        return group.member_counter

    # ----- Group Validation -----
    @staticmethod
    def validate_password(group, provided_password):
        """
        Validate the group password.

        Args:
            group (Group): Group object.
            provided_password (str): Password to validate.

        Raises:
            generate_error: If the password is incorrect.
        """
        # if not bcrypt.check_password_hash(group.group_pw, provided_password):
        if bcrypt.check_password_hash(group.group_pw, provided_password):
            return True
        else:
            return False

    @staticmethod
    def check_group_limit(group):
        """
        Check if the group has reached its member limit.

        Args:
            group (Group): Group object.

        Raises:
            generate_error: If the group is already full.
        """
        if group.member_counter >= group.member_maxCnt:
            return generate_error(409, "Group is already full.")
