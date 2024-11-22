import unittest
from app import create_app, db
from app.models import User

class UserTest(unittest.TestCase):
    def setUp(self):
        self.app = create_app()
        self.client = self.app.test_client()
        with self.app.app_context():
            db.create_all()


    def tearDown(self):
        with self.app.app_context():
            db.session.remove()
            db.drop_all()

    def test_create_user(self):
        response = self.client.post('/users/', json={'github_id': 'TestUser', 'repository_name': 'Test_Repo'})
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json['github_id'], 'TestUser')
        self.assertEqual(response.json['repository_name'], 'Test_Repo')

    def test_get_user(self):
        # Retrieve the user seeded during setUp
        response = self.client.get('/users/?id=9')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json['github_id'], 'Layla7120')
        self.assertEqual(response.json['repository_name'], 'Code_Tests')

    def test_get_user_not_found(self):
        # Attempt to retrieve a user that doesn't exist
        response = self.client.get('/users/?id=999')
        self.assertEqual(response.status_code, 404)
        self.assertIn('error', response.json)
        self.assertEqual(response.json['error'], 'User not found')

