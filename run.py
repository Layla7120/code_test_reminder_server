import os

from flask import jsonify, request
from flask_socketio import SocketIO, emit
from werkzeug.exceptions import HTTPException

from app import create_app

app = create_app()
# socketio = SocketIO(app)

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

# @socketio.on('connect')
# def handle_connect():
#     user_id = request.args.get('user_id')
#     if user_id:
#         # 사용자 ID를 소켓 세션에 저장
#         emit('connected', {'message': f'User {user_id} connected!'})
#         socketio.enter_room(request.sid, user_id)
#
# @socketio.on('disconnect')
# def handle_disconnect():
#     print('User disconnected!')
#
# @app.route('/trigger_notification', methods=['POST'])
# def trigger_notification():
#     data = request.json
#     print(data)
#     message = data.get('message', '알림!')
#     recipient_id = data.get('recipient_id')
#
#     socketio.emit('notification', {'message': message}, room=recipient_id)
#     return {'status': 'success'}, 200

if __name__ == '__main__':
    port = int(os.environ.get("PORT", 8080))
    app.run(host="0.0.0.0", port=port)
    # socketio.run(app, host="0.0.0.0", port=port, allow_unsafe_werkzeug=True)
