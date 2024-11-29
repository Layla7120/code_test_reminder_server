from app import db, bcrypt
from app.models import Group

class GroupService:
    """Service class for managing Group-related operations."""

    # ----- Group Retrieval Methods -----
    @staticmethod
    def get_group_by_name(group_name):
        """Retrieve group details by group ID."""
        return Group.query.get(group_name)

    @staticmethod
    def get_group_by_id(group_id):
        """Alias for getting group details by ID."""
        return Group.query.get(group_id)

    @staticmethod
    def search_group_name_starts_with(prefix):
        """Search groups with names starting with a given prefix."""
        group_list = Group.query.filter(Group.group_name.startswith(prefix)).all()
        result = [
            {
                "group_id": group.group_id,
                "group_name": group.group_name,
                "group_pw": group.group_pw
            }
            for group in group_list
        ]
        return result

    # ----- Group Creation and Deletion -----
    @staticmethod
    def create_group(group_name, group_pw, member_max_cnt, group_owner_id):
        """Create a new group."""
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
        """Increment the member counter for a group."""
        group = Group.query.get(group_id)
        if group:
            group.member_counter += 1
            db.session.commit()
            return group.member_counter
        raise ValueError(f"Group with ID {group_id} not found.")

    @staticmethod
    def decrement_group_counter(group_id):
        """Decrement the member counter for a group."""
        group = Group.query.get(group_id)
        if group:
            group.member_counter -= 1
            db.session.commit()
            return group.member_counter
        raise ValueError(f"Group with ID {group_id} not found.")

    @staticmethod
    def validate_password(group, provided_password):
        if not bcrypt.check_password_hash(group.group_pw, provided_password):
            generate_error(403, "Password is incorrect.")

    @staticmethod
    def check_group_limit(group):
        if group.member_counter + 1 > group.member_maxCnt:
            generate_error(409, "Group is already full.")