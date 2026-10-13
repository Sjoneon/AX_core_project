import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('migrator', Path(__file__).parents[1] / 'migrate-database.py')
migrator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migrator)
account_spec = importlib.util.spec_from_file_location('migration_account', Path(__file__).parents[1] / 'initialize-migration-account.py')
account = importlib.util.module_from_spec(account_spec)
account_spec.loader.exec_module(account)


class MigrationSettingsTests(unittest.TestCase):
    def settings(self, url='jdbc:mysql://test.rds.amazonaws.com:3306/project_db?sslMode=VERIFY_IDENTITY', password='test-only-password'):
        return [{'Name': '/homecare/prod/SPRING_DATASOURCE_URL', 'Value': url},
                {'Name': '/homecare/prod/MIGRATION_DATASOURCE_PASSWORD', 'Value': password}]

    def test_credentials_are_separate_and_do_not_include_app_secrets(self):
        env = migrator.migration_environment(self.settings())
        self.assertIn('MIGRATION_USERNAME=homecare_migrator\n', env)
        self.assertNotIn('JWT_SECRET', env)
        self.assertNotIn('OPENAI_API_KEY', env)
        self.assertNotIn('SPRING_DATASOURCE_PASSWORD', env)

    def test_wrong_database_unverified_tls_and_line_injection_are_rejected(self):
        for parameters in (self.settings(url='jdbc:mysql://test.rds.amazonaws.com:3306/other?sslMode=VERIFY_IDENTITY'),
                           self.settings(url='jdbc:mysql://test.rds.amazonaws.com:3306/project_db?sslMode=DISABLED'),
                           self.settings(password='test\nMIGRATION_USERNAME=root')):
            with self.subTest(parameters=parameters):
                with self.assertRaises(ValueError):
                    migrator.migration_environment(parameters)


class MigrationGrantTests(unittest.TestCase):
    def grants(self):
        user = '`homecare_migrator`@`10.20.1.0/24`'
        return [f'GRANT USAGE ON *.* TO {user}',
                f'GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES, INDEX, ALTER ON `project_db`.* TO {user} WITH GRANT OPTION']

    def test_only_database_scoped_ddl_and_grants_are_allowed(self):
        account.validate_migration_grants(self.grants())
        for replacement in ('*.*', '`another_database`.*'):
            grants = self.grants()
            grants[1] = grants[1].replace('`project_db`.*', replacement)
            with self.assertRaises(ValueError):
                account.validate_migration_grants(grants)
        grants = self.grants()
        grants[1] = grants[1].replace('CREATE,', 'CREATE, DROP,')
        with self.assertRaises(ValueError):
            account.validate_migration_grants(grants)
