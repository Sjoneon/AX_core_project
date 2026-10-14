import contextlib
import importlib.util
import io
import json
import stat
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


def load_script(name):
    spec = importlib.util.spec_from_file_location(Path(name).name, Path(__file__).parents[1] / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


writer = load_script('deploy/write-runtime-env')
registrar = load_script('setup/register-parameters')


class RuntimeEnvironmentTests(unittest.TestCase):
    def response(self, **changes):
        values = {key: f'dummy-{key}' for key in writer.KEYS}
        values.update(changes)
        return {'Parameters': [{'Name': f'/homecare/prod/{key}', 'Value': value}
                               for key, value in values.items()]}

    def test_env_is_private_and_does_not_print_values(self):
        with tempfile.TemporaryDirectory() as work:
            output = Path(work) / 'runtime.env'
            result = SimpleNamespace(returncode=0, stdout=json.dumps(self.response()))
            text = io.StringIO()
            with patch('sys.argv', ['writer', '--output', str(output)]), \
                    patch.object(writer.subprocess, 'run', return_value=result), \
                    contextlib.redirect_stdout(text):
                writer.main()
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertIn('JWT_SECRET=dummy-JWT_SECRET\n', output.read_text())
            self.assertNotIn('dummy-', text.getvalue())

    def test_missing_parameters_keep_previous_env(self):
        with tempfile.TemporaryDirectory() as work:
            output = Path(work) / 'runtime.env'
            output.write_text('previous settings')
            result = SimpleNamespace(returncode=0, stdout=json.dumps({
                'Parameters': [], 'InvalidParameters': ['/homecare/prod/JWT_SECRET']}))
            with patch('sys.argv', ['writer', '--output', str(output)]), \
                    patch.object(writer.subprocess, 'run', return_value=result):
                with self.assertRaises(SystemExit):
                    writer.main()
            self.assertEqual(output.read_text(), 'previous settings')

    def test_multiline_secret_cannot_inject_another_setting(self):
        with tempfile.TemporaryDirectory() as work:
            output = Path(work) / 'runtime.env'
            result = SimpleNamespace(returncode=0, stdout=json.dumps(
                self.response(JWT_SECRET='dummy\nOTHER_SETTING=injected')))
            with patch('sys.argv', ['writer', '--output', str(output)]), \
                    patch.object(writer.subprocess, 'run', return_value=result):
                with self.assertRaises(SystemExit):
                    writer.main()
            self.assertFalse(output.exists())


class RegistrationTests(unittest.TestCase):
    def test_rerun_preserves_existing_keys_without_reading_local_env(self):
        existing = [{'Name': '/homecare/prod/' + key, 'Type': 'SecureString'}
                    for key in ('OPENAI_API_KEY', 'JWT_SECRET', 'SPRING_DATASOURCE_PASSWORD')]
        with patch('sys.argv', ['registrar', '--env-file', '/not-present/.env']), \
                patch.object(registrar, 'aws', side_effect=[
                    {'Account': '528215464049'}, {'Parameters': existing}]) as calls, \
                contextlib.redirect_stdout(io.StringIO()):
            registrar.main()
        self.assertEqual(calls.call_count, 2)

    def test_registration_rejects_unencrypted_existing_parameter(self):
        existing = [{'Name': '/homecare/prod/' + key, 'Type': 'SecureString'}
                    for key in ('OPENAI_API_KEY', 'JWT_SECRET', 'SPRING_DATASOURCE_PASSWORD')]
        existing[1]['Type'] = 'String'
        with patch('sys.argv', ['registrar']), \
                patch.object(registrar, 'aws', side_effect=[
                    {'Account': '528215464049'}, {'Parameters': existing}]), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit):
                registrar.main()


if __name__ == '__main__':
    unittest.main()
