#!/usr/bin/env python3
"""Run the RDS initializer via SSM, then remove its expiring admin-secret policy."""
import base64
import argparse
import datetime
import json
import shlex
import secrets
import subprocess
import time
from pathlib import Path

REGION = 'ap-northeast-2'
INSTANCE = 'i-0223afa15057a76b2'
ROLE = 'homecare-prod-ec2'
POLICY = 'homecare-rds-initialization-temporary'


def aws(*arguments):
    result = subprocess.run(['aws', *arguments, '--region', REGION, '--output', 'json'],
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f'AWS {arguments[0]} {arguments[1]} failed; output withheld.')
    return json.loads(result.stdout) if result.stdout.strip() else {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--migration-account', action='store_true', help='Provision the separate Flyway account only.')
    args = parser.parse_args()
    if aws('sts', 'get-caller-identity')['Account'] != '528215464049':
        raise SystemExit('Unexpected AWS account.')
    db = aws('rds', 'describe-db-instances', '--db-instance-identifier', 'homecare-prod-mysql')['DBInstances'][0]
    secret_arn = db['MasterUserSecret']['SecretArn']
    if POLICY in aws('iam', 'list-role-policies', '--role-name', ROLE)['PolicyNames']:
        raise SystemExit('Temporary policy already exists; inspect/remove it before rerunning.')
    if args.migration_account:
        name = '/homecare/prod/MIGRATION_DATASOURCE_PASSWORD'
        existing = aws('ssm', 'describe-parameters', '--parameter-filters',
                       json.dumps([{'Key': 'Name', 'Option': 'Equals', 'Values': [name]}]))['Parameters']
        if not existing:
            password = secrets.token_urlsafe(48)
            aws('ssm', 'put-parameter', '--name', name, '--type', 'SecureString', '--value', password)
            del password
            print('Created the migration SecureString; value not printed or stored locally.', flush=True)
        elif len(existing) != 1 or existing[0]['Type'] != 'SecureString':
            raise SystemExit('Unexpected migration parameter type.')
    expiry = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=30)
    policy = {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Action': 'secretsmanager:GetSecretValue', 'Resource': secret_arn,
        'Condition': {'DateLessThan': {'aws:CurrentTime': expiry.strftime('%Y-%m-%dT%H:%M:%SZ')}}}]}
    aws('iam', 'put-role-policy', '--role-name', ROLE, '--policy-name', POLICY,
        '--policy-document', json.dumps(policy))
    try:
        print('Granted temporary read of the one RDS admin secret; expires in 30 minutes.', flush=True)
        time.sleep(10)
        commands = ['set -eu', 'install -d -m 0755 /usr/local/lib/homecare/sql']
        root = Path(__file__).resolve().parent
        files = [
                ('initialize-rds.py', '/usr/local/lib/homecare/initialize-rds.py'),
                ('sql/V001__initial_schema.sql', '/usr/local/lib/homecare/sql/V001__initial_schema.sql')]
        target = 'initialize-rds.py'
        if args.migration_account:
            target = 'initialize-migration-account.py'
            files.append((target, '/usr/local/lib/homecare/' + target))
        for source, destination in files:
            encoded = base64.b64encode((root / source).read_bytes()).decode()
            commands.append(f"printf '%s' '{encoded}' | base64 -d > {shlex.quote(destination)}")
            commands.append(f'chmod 0644 {shlex.quote(destination)}')
        commands.append('python3 /usr/local/lib/homecare/' + target + ' --master-secret-arn '
                        + shlex.quote(secret_arn) + ' --sql /usr/local/lib/homecare/sql/V001__initial_schema.sql')
        response = aws('ssm', 'send-command', '--instance-ids', INSTANCE,
                       '--document-name', 'AWS-RunShellScript', '--comment', 'Homecare database account initialization',
                       '--parameters', json.dumps({'commands': commands, 'executionTimeout': ['300']}))
        command_id = response['Command']['CommandId']
        print('SSM initialization command: ' + command_id, flush=True)
        time.sleep(5)
        for _ in range(90):
            result = aws('ssm', 'get-command-invocation', '--command-id', command_id, '--instance-id', INSTANCE)
            if result['Status'] not in ('Pending', 'InProgress', 'Delayed'):
                print(result['StandardOutputContent'].strip(), flush=True)
                if result['Status'] != 'Success':
                    print(result['StandardErrorContent'].strip(), flush=True)
                    raise SystemExit('RDS initialization did not succeed.')
                return
            time.sleep(5)
        raise SystemExit('Initialization result timed out; inspect the SSM command before rerunning.')
    finally:
        aws('iam', 'delete-role-policy', '--role-name', ROLE, '--policy-name', POLICY)
        print('Removed temporary RDS administrator-secret read policy.', flush=True)


if __name__ == '__main__':
    main()
