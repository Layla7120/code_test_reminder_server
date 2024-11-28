import os

from flask import jsonify
from werkzeug.exceptions import HTTPException

from app import create_app

app = create_app()

@app.errorhandler(HTTPException)
def handle_exception(e):
    """Return custom error message and status code."""
    response = e.get_response()

    response.data = jsonify({
        "code": e.code,
        "status": e.name,
        "message": e.description
    }).data
    response.content_type = "application/json"
    return response

if __name__ == '__main__':
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port)
