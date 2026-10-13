"""Run the deployment transaction against disposable files and fake AWS/Docker."""
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

OPS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ci_release', OPS / 'ci-release.py')
ci_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci_release)


class DeploymentTests(unittest.TestCase):
    def run_deployment(self, failure='', previous=True):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / 'homecare'
            config = home / 'config'
            config.mkdir(parents=True)
            (config / 'runtime.env').write_text('JWT_SECRET=previous-test-secret\n')
            (config / 'rds-truststore.p12').touch()
            cert = root / 'cert'
            cert.mkdir()
            for name in ('fullchain.pem', 'privkey.pem'):
                (cert / name).touch()
            for name in ('old', 'new'):
                directory = home / 'releases' / name
                directory.mkdir(parents=True)
                for file in ('compose.prod.yaml', 'write-runtime-env.py', 'migrate-database.py'):
                    (directory / file).touch()
                (directory / 'images.env').write_text('BACKEND_IMAGE=test-image\n')
            if previous:
                (home / 'current').symlink_to(home / 'releases/old')
            script = root / 'deploy.sh'
            script.write_text((OPS / 'deploy-service.sh').read_text()
                              .replace('/opt/homecare', str(home))
                              .replace('/etc/letsencrypt/live/homecare-api', str(cert))
                              .replace('mv -Tf ', 'mv -f '))  # macOS has no GNU -T.
            # macOS mv follows directory symlinks; use a small atomic rename shim.
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            shim = f'''#!{sys.executable}
import json,os,signal,sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
home=Path(os.environ['TEST_HOME'])
with (home/'calls.jsonl').open('a') as stream:
    stream.write(json.dumps([name,*args])+'\\n')
failure=os.environ['TEST_FAILURE']
if name=='id': print('0')
elif name=='flock': sys.exit(1 if failure=='lock' else 0)
elif name=='mv': os.replace(args[-2],args[-1])
elif name=='aws': print('fake-test-password')
elif name=='python3' and args[0].endswith('write-runtime-env.py'):
    if failure=='parameters': sys.exit(1)
    Path(args[-1]).write_text('JWT_SECRET=new-test-secret\\n')
elif name=='python3' and args[0].endswith('migrate-database.py'):
    sys.exit(1 if failure=='migration' else 0)
elif name=='python3': os.execv({sys.executable!r},[{sys.executable!r},*args])
elif name=='curl': print('{{"status":"UP"}}')
elif name=='docker':
    if 'login' in args: sys.stdin.read()
    is_new=any('/releases/new/' in arg for arg in args)
    if failure=='signal' and is_new and 'up' in args: os.kill(os.getppid(),signal.SIGTERM)
    if (failure=='pull' and 'pull' in args) or (failure=='health' and is_new and 'up' in args): sys.exit(1)
'''
            for name in ('id', 'flock', 'mv', 'aws', 'python3', 'curl', 'docker'):
                file = bin_dir / name
                file.write_text(shim)
                file.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                       TEST_HOME=str(home), TEST_FAILURE=failure)
            result = subprocess.run(['bash', str(script), str(home / 'releases/new')],
                                    env=env, capture_output=True, text=True)
            current = (home / 'current').resolve().name if (home / 'current').exists() else None
            settings = (config / 'runtime.env').read_text()
            calls = [json.loads(line) for line in (home / 'calls.jsonl').read_text().splitlines()]
            return result, current, settings, calls

    def test_success_promotes_only_after_health(self):
        result, current, settings, calls = self.run_deployment()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, 'new')
        self.assertIn('new-test-secret', settings)
        health = next(i for i, call in enumerate(calls) if call[0] == 'curl')
        promotion = next(i for i, call in enumerate(calls) if call[0] == 'mv' and call[-1].endswith('/current'))
        self.assertLess(health, promotion)
        self.assertNotIn('test-secret', result.stdout + result.stderr)

    def test_unhealthy_release_restores_previous_images_and_settings(self):
        for failure in ('health', 'signal'):
            with self.subTest(failure=failure):
                result, current, settings, calls = self.run_deployment(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(current, 'old')
                self.assertEqual(settings, 'JWT_SECRET=previous-test-secret\n')
                self.assertIn('ROLLBACK_OK', result.stderr)
                self.assertTrue(any(call[0] == 'docker' and 'up' in call and
                                    any('/releases/old/' in arg for arg in call) for call in calls))
                self.assertNotIn('test-secret', result.stdout + result.stderr)

    def test_preparation_failure_never_replaces_containers_or_settings(self):
        for failure in ('parameters', 'pull', 'lock', 'migration'):
            with self.subTest(failure=failure):
                result, current, settings, calls = self.run_deployment(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(current, 'old')
                self.assertEqual(settings, 'JWT_SECRET=previous-test-secret\n')
                self.assertFalse(any(call[0] == 'docker' and 'up' in call for call in calls))

    def test_sql_runs_after_image_pull_and_before_container_replacement(self):
        result, _, _, calls = self.run_deployment()
        self.assertEqual(result.returncode, 0, result.stderr)
        pull = next(i for i, call in enumerate(calls) if call[0] == 'docker' and 'pull' in call)
        migrate = next(i for i, call in enumerate(calls) if call[0] == 'python3' and call[1].endswith('migrate-database.py'))
        up = next(i for i, call in enumerate(calls) if call[0] == 'docker' and 'up' in call)
        self.assertLess(pull, migrate)
        self.assertLess(migrate, up)

    def test_first_deploy_failure_is_reported_without_fake_rollback(self):
        result, current, _, _ = self.run_deployment('health', previous=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(current)
        self.assertIn('FIRST_DEPLOY_FAILED', result.stderr)
        self.assertNotIn('ROLLBACK_OK', result.stderr)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        stdout = patch('sys.stdout', new=io.StringIO())
        stdout.start()
        self.addCleanup(stdout.stop)

    @patch.object(ci_release.urllib.request, 'urlopen')
    @patch.object(ci_release, 'wait_for_deployment')
    @patch.object(ci_release, 'aws')
    @patch.object(ci_release, 'image_digest')
    @patch.object(ci_release.subprocess, 'check_output')
    @patch.object(ci_release.subprocess, 'run')
    def test_same_commit_reuses_both_images_without_build_or_push(self, run, output, digest, aws, wait, urlopen):
        digest.return_value = 'sha256:' + 'a' * 64
        output.return_value = b'fake-login-password'
        aws.return_value = {'Command': {'CommandId': 'command'}}
        urlopen.return_value = io.BytesIO(b'{"status":"UP"}')
        env = {'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '2',
               'GITHUB_REF': 'refs/heads/main', 'GITHUB_REPOSITORY': 'test/repo',
               'ECR_REGISTRY': 'registry.example', 'EC2_INSTANCE_ID': 'instance',
               'ECR_BACKEND_REPOSITORY': 'backend', 'ECR_NGINX_REPOSITORY': 'nginx',
               'RELEASE_BUCKET': 'test-bucket', 'GITHUB_STEP_SUMMARY': ''}
        with patch.dict(os.environ, env):
            ci_release.main()
        self.assertEqual(digest.call_count, 2)
        self.assertFalse(any(call.args[0][:2] in (['docker', 'build'], ['docker', 'push'])
                             for call in run.call_args_list))
        wait.assert_called_once_with('command', 'instance')

    @patch.object(ci_release.subprocess, 'run')
    def test_missing_tag_can_be_built_but_permission_failure_cannot(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, '', '(ImageNotFoundException)')
        self.assertIsNone(ci_release.image_digest('backend', 'commit'))
        run.return_value = subprocess.CompletedProcess([], 1, '', '(AccessDeniedException)')
        with self.assertRaises(RuntimeError):
            ci_release.image_digest('backend', 'commit')

    @patch.object(ci_release.time, 'sleep')
    @patch.object(ci_release, 'aws')
    def test_ssm_waits_for_execution_and_rejects_failed_remote_command(self, aws, sleep):
        aws.side_effect = [RuntimeError('(InvocationDoesNotExist)'), {'Status': 'InProgress'},
                           {'Status': 'Failed', 'ResponseCode': 1, 'StandardErrorContent': 'ROLLBACK_OK'}]
        with self.assertRaisesRegex(RuntimeError, 'Failed'):
            ci_release.wait_for_deployment('command', 'instance')
        self.assertEqual(aws.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    @patch.object(ci_release, 'aws')
    def test_ssm_success_also_requires_zero_exit_code(self, aws):
        aws.return_value = {'Status': 'Success', 'ResponseCode': 1}
        with self.assertRaises(RuntimeError):
            ci_release.wait_for_deployment('command', 'instance')


if __name__ == '__main__':
    unittest.main()
