from functools import wraps

from flask import abort, jsonify, make_response
from sqlalchemy.exc import IntegrityError

from app import db

def generate_error(status_code, description):
    return make_response(jsonify({"error": description}), status_code)

def handle_errors(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        try:
            return f(*args, **kwargs)
        except IntegrityError:
            db.session.rollback()
            return generate_error(500, "A database error occurred.")
        except Exception as e:
            return generate_error(500, f"An unexpected error occurred: {str(e)}")
    return decorated_function
