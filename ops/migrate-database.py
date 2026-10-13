#!/usr/bin/env python3
"""Run only the tested image's Flyway entry point with separate migration credentials."""
import argparse
import json
import os
import re
import subprocess
import tempfile
import uuid
from pathlib import Path


def migration_environment(parameters):
    values = {p['Name'].rsplit('/', 1)[-1]: p['Value'] for p in parameters}
    url = values['SPRING_DATASOURCE_URL']
    password = values['MIGRATION_DATASOURCE_PASSWORD']
    if not re.fullmatch(r'jdbc:mysql://[a-z0-9.-]+\.rds\.amazonaws\.com:3306/project_db\?.+', url):
        raise ValueError('Unexpected RDS endpoint')
    if 'sslMode=VERIFY_IDENTITY' not in url:
        raise ValueError('TLS identity verification is required')
    if not password or any(c in password + url for c in ('\r', '\n', '\x00')):
        raise ValueError('Invalid migration settings')
    url += '&connectTimeout=10000&socketTimeout=180000&sessionVariables=lock_wait_timeout=30,innodb_lock_wait_timeout=30'
    return f'MIGRATION_JDBC_URL={url}\nMIGRATION_USERNAME=homecare_migrator\nMIGRATION_PASSWORD={password}\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'528215464049\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/homecare-prod/backend@sha256:[0-9a-f]{64}', args.image):
        raise ValueError('Expected an immutable backend image')
    result = subprocess.run(['aws', 'ssm', 'get-parameters', '--region', 'ap-northeast-2',
                             '--names', '/homecare/prod/SPRING_DATASOURCE_URL',
                             '/homecare/prod/MIGRATION_DATASOURCE_PASSWORD', '--with-decryption', '--output', 'json'],
                            capture_output=True, text=True, check=True)
    response = json.loads(result.stdout)
    if response.get('InvalidParameters'):
        raise ValueError('Migration credentials have not been provisioned')
    # Docker bridge reaches private RDS; IMDS hop limit prevents container credential retrieval.
    # Only this short-lived container receives the DDL credential. No HTTP server is started.
    fd, name = tempfile.mkstemp(prefix='.migration-', dir='/opt/homecare/config')
    container = 'homecare-migration-' + uuid.uuid4().hex
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(migration_environment(response['Parameters']))
        command = ['docker', 'run', '--rm', '--name', container, '--network', 'bridge', '--user', '10001:10001',
                   '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                   '--memory', '384m', '--tmpfs', '/tmp:size=32m,mode=1777', '--env-file', name,
                   '-e', 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65',
                   '-v', '/opt/homecare/config/rds-truststore.p12:/app/config/rds-truststore.p12:ro',
                   '--entrypoint', 'java', args.image,
                   '-Dloader.main=com.project.backend.migration.DatabaseMigration',
                   '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher']
        result = subprocess.run(command, capture_output=True, text=True, timeout=600)
        # Never emit raw Flyway output, which may contain SQL or connection information.
        if result.returncode:
            raise RuntimeError('Migration did not succeed; inspect history before retrying')
        if 'DB_MIGRATION_OK:' not in result.stdout:
            raise RuntimeError('Migration success marker missing')
        print(next(line for line in result.stdout.splitlines() if line.startswith('DB_MIGRATION_OK:')))
    finally:
        try:
            subprocess.run(['docker', 'rm', '-f', container], capture_output=True, timeout=30)
        finally:
            Path(name).unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'DB_MIGRATION_FAILED ({type(error).__name__}); containers unchanged. Inspect DB history; no automatic repair.') from None
