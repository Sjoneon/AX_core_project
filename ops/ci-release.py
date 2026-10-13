#!/usr/bin/env python3
"""Publish this CI run's images and wait for an EC2 deployment to finish."""
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def aws(*args):
    result = subprocess.run(['aws', *args, '--output', 'json'], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'AWS {args[0]} {args[1]} failed: {result.stderr.strip()}')
    return json.loads(result.stdout) if result.stdout.strip() else None


def image_digest(repository, tag):
    result = subprocess.run(
        ['aws', 'ecr', 'describe-images', '--repository-name', repository,
         '--image-ids', f'imageTag={tag}', '--output', 'json'], capture_output=True, text=True,
    )
    if result.returncode:
        if '(ImageNotFoundException)' in result.stderr:
            return None
        raise RuntimeError('Cannot check the existing ECR image; deployment stopped.')
    digest = json.loads(result.stdout)['imageDetails'][0]['imageDigest']
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise RuntimeError('ECR returned an invalid image digest.')
    return digest


def wait_for_deployment(command_id, instance_id):
    deadline = time.monotonic() + 1320
    while time.monotonic() < deadline:
        try:
            result = aws('ssm', 'get-command-invocation', '--command-id', command_id,
                         '--instance-id', instance_id)
        except RuntimeError as error:
            if '(InvocationDoesNotExist)' not in str(error):
                raise
            time.sleep(5)
            continue
        status = result['Status']
        if status in ('Pending', 'InProgress', 'Delayed', 'Cancelling'):
            time.sleep(5)
            continue
        # Scripts print only health/deployment information, never env files or app logs.
        print(result.get('StandardOutputContent', ''), flush=True)
        print(result.get('StandardErrorContent', ''), flush=True)
        if status != 'Success' or result.get('ResponseCode') != 0:
            raise RuntimeError(f'EC2 deployment failed: {status}; check the rollback result above.')
        return
    raise RuntimeError('Timed out waiting for SSM. The remote command was not cancelled; inspect it before retrying.')


def main():
    sha = os.environ['GITHUB_SHA']
    run_id, attempt = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdecimal() or not attempt.isdecimal():
        raise RuntimeError('Invalid GitHub release identity.')
    if os.environ['GITHUB_REF'] != 'refs/heads/main':
        raise RuntimeError('Production deployments require main.')
    release_id = f'{sha}-{run_id}-{attempt}'
    registry = os.environ['ECR_REGISTRY']
    instance = os.environ['EC2_INSTANCE_ID']
    images = {}
    with tempfile.TemporaryDirectory(prefix='homecare-release-') as temporary:
        work = Path(temporary)
        auth = work / 'docker-auth'
        auth.mkdir(mode=0o700)
        os.environ['DOCKER_CONFIG'] = str(auth)
        password = subprocess.check_output(['aws', 'ecr', 'get-login-password'])
        subprocess.run(['docker', 'login', '--username', 'AWS', '--password-stdin', registry],
                       input=password, check=True, capture_output=True)
        del password
        for service, context in (('backend', 'backend'), ('nginx', 'proxy')):
            repository = os.environ[f'ECR_{service.upper()}_REPOSITORY']
            digest = image_digest(repository, sha)
            if digest is None:
                tag = f'{registry}/{repository}:{sha}'
                subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-t', tag,
                                str(ROOT / context)], check=True)
                subprocess.run(['docker', 'push', tag], check=True)
                digest = image_digest(repository, sha)
                if digest is None:
                    raise RuntimeError('Published image is missing from ECR.')
            else:
                print(f'Reusing immutable {service} image for {sha}.', flush=True)
            images[service] = f'{registry}/{repository}@{digest}'

        release = work / 'release'
        release.mkdir()
        for name in ('compose.prod.yaml', 'deploy-service.sh', 'write-runtime-env.py', 'verify-service.py', 'migrate-database.py'):
            shutil.copyfile(ROOT / 'ops' / name, release / name)
        (release / 'images.env').write_text(
            f'RELEASE_ID={release_id}\nBACKEND_IMAGE={images["backend"]}\nNGINX_IMAGE={images["nginx"]}\n')
        metadata = {'release_id': release_id, 'source_commit': sha, 'images': images,
                    'workflow_run': f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{run_id}'}
        (release / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
        archive = work / 'release.tar.gz'
        with tarfile.open(archive, 'w:gz') as bundle:
            for file in sorted(release.iterdir()):
                bundle.add(file, arcname=file.name)
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        uri = f's3://{os.environ["RELEASE_BUCKET"]}/releases/{release_id}/release.tar.gz'
        subprocess.run(['aws', 's3', 'cp', str(archive), uri, '--only-show-errors'], check=True)

        directory = f'/opt/homecare/releases/{release_id}'
        commands = [
            'set -eu', 'umask 077', 'work=$(mktemp -d)', 'trap \'rm -rf "$work"\' EXIT',
            f'aws s3 cp {shlex.quote(uri)} "$work/release.tar.gz" --only-show-errors',
            f'printf \'%s  %s\\n\' {checksum} "$work/release.tar.gz" | sha256sum -c -',
            f'mkdir -p {shlex.quote(directory)}',
            f'tar -xzf "$work/release.tar.gz" -C {shlex.quote(directory)} --no-same-owner',
            f'bash {shlex.quote(directory + "/deploy-service.sh")} {shlex.quote(directory)}',
        ]
        result = aws('ssm', 'send-command', '--instance-ids', instance,
                     '--document-name', 'AWS-RunShellScript', '--timeout-seconds', '120',
                     '--parameters', json.dumps({'commands': commands, 'executionTimeout': ['1200']}))
        command_id = result['Command']['CommandId']
        print(f'SSM command: {command_id}; release: {release_id}; SHA-256: {checksum}', flush=True)
        wait_for_deployment(command_id, instance)

        with urllib.request.urlopen('https://43.202.80.116/actuator/health', timeout=10) as response:
            if json.load(response)['status'] != 'UP':
                raise RuntimeError('External HTTPS health check failed.')
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a') as stream:
                stream.write(f'### Deployment succeeded\n\nRelease: `{release_id}`\n\n'
                             f'SSM command: `{command_id}`\n\nArtifact SHA-256: `{checksum}`\n\n'
                             f'- Backend: `{images["backend"]}`\n- Nginx: `{images["nginx"]}`\n')
        print('PASS: remote deployment and trusted external HTTPS health.', flush=True)


if __name__ == '__main__':
    main()
