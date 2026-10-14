import contextlib
import hashlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location('initializer', Path(__file__).parents[1] / 'rds/initialize-rds.py')
initializer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(initializer)
verify_spec = importlib.util.spec_from_file_location('verifier', Path(__file__).parents[1] / 'rds/verify-rds.py')
verifier = importlib.util.module_from_spec(verify_spec)
verify_spec.loader.exec_module(verifier)


class SchemaInitializationTests(unittest.TestCase):
    def test_applies_empty_database_and_records_checksum(self):
        cursor = Mock()
        cursor.fetchall.return_value = []
        sql = 'CREATE TABLE app_users (id BIGINT);'
        with contextlib.redirect_stdout(io.StringIO()):
            initializer.apply_schema(cursor, sql)
        self.assertEqual(cursor.execute.call_args.args[1],
                         ('V001', hashlib.sha256(sql.encode()).hexdigest()))

    def test_repeat_does_not_execute_ddl(self):
        cursor = Mock()
        cursor.fetchall.return_value = [(table,) for table in (*initializer.TABLES, 'schema_migrations')]
        sql = 'unchanged SQL'
        cursor.fetchone.return_value = (hashlib.sha256(sql.encode()).hexdigest(),)
        with contextlib.redirect_stdout(io.StringIO()):
            initializer.apply_schema(cursor, sql)
        self.assertEqual(cursor.execute.call_count, 2)

    def test_changed_applied_sql_is_rejected(self):
        cursor = Mock()
        cursor.fetchall.return_value = [(table,) for table in (*initializer.TABLES, 'schema_migrations')]
        cursor.fetchone.return_value = ('different checksum',)
        with self.assertRaises(SystemExit):
            initializer.apply_schema(cursor, 'modified SQL')
        self.assertEqual(cursor.execute.call_count, 2)

    def test_audited_previous_v001_is_preserved_without_ddl_or_checksum_rewrite(self):
        cursor = Mock()
        cursor.fetchall.return_value = [(table,) for table in (*initializer.TABLES, 'schema_migrations')]
        cursor.fetchone.return_value = (initializer.V001_PREVIOUS_SHA256,)
        sql = (Path(__file__).parents[1] / 'rds/sql/V001__initial_schema.sql').read_text(encoding='utf-8')
        self.assertEqual(hashlib.sha256(sql.encode()).hexdigest(), initializer.V001_CURRENT_SHA256)
        with contextlib.redirect_stdout(io.StringIO()):
            initializer.apply_schema(cursor, sql)
        self.assertEqual(cursor.execute.call_count, 2)

    def test_previous_checksum_does_not_allow_arbitrary_modified_sql(self):
        cursor = Mock()
        cursor.fetchall.return_value = [(table,) for table in (*initializer.TABLES, 'schema_migrations')]
        cursor.fetchone.return_value = (initializer.V001_PREVIOUS_SHA256,)
        with self.assertRaises(SystemExit):
            initializer.apply_schema(cursor, 'unexpected new SQL')
        self.assertEqual(cursor.execute.call_count, 2)

    def test_partial_or_unrelated_schema_is_not_modified(self):
        for tables in [('app_users',), ('unrelated_table',)]:
            with self.subTest(tables=tables):
                cursor = Mock()
                cursor.fetchall.return_value = [(table,) for table in tables]
                with self.assertRaises(SystemExit):
                    initializer.apply_schema(cursor, 'SQL')
                self.assertEqual(cursor.execute.call_count, 1)


class AppGrantTests(unittest.TestCase):
    def grants(self):
        account = '`homecare_app`@`10.20.1.0/24`'
        return [f'GRANT USAGE ON *.* TO {account}', *[
            f'GRANT SELECT, INSERT, UPDATE, DELETE ON `project_db`.`{table}` TO {account}'
            for table in initializer.TABLES]]

    def test_mysql84_grants_do_not_include_require_ssl(self):
        initializer.validate_grants(self.grants())

    def test_extra_global_privileges_are_rejected(self):
        grants = self.grants()
        grants[0] = 'GRANT ALL PRIVILEGES ON *.* TO `homecare_app`@`10.20.1.0/24`'
        with self.assertRaises(SystemExit):
            initializer.validate_grants(grants)

    def test_schema_wide_privileges_are_rejected(self):
        grants = self.grants()
        grants[1] = 'GRANT SELECT, INSERT, UPDATE, DELETE ON `project_db`.* TO `homecare_app`@`10.20.1.0/24`'
        with self.assertRaises(SystemExit):
            initializer.validate_grants(grants)


class AppStartupTests(unittest.TestCase):
    def test_health_retries_connection_reset_during_startup(self):
        with patch.object(verifier, 'request', side_effect=[ConnectionResetError(), {'status': 'UP'}]) as request, \
                patch.object(verifier.time, 'sleep'):
            verifier.wait_for_health()
        self.assertEqual(request.call_count, 2)


if __name__ == '__main__':
    unittest.main()
