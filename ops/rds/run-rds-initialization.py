#!/usr/bin/env python3
"""SSM으로 RDS 초기화 실행 후 임시 관리자 비밀 조회 권한 해제"""
import base64
import datetime
import json
import shlex
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
    """기한이 있는 관리자 권한 부여와 SSM 초기화 결과 확인"""
    # 1. 대상 AWS 계정과 기존 임시 권한 확인
    if aws('sts', 'get-caller-identity')['Account'] != '528215464049':
        raise SystemExit('Unexpected AWS account.')
    db = aws('rds', 'describe-db-instances', '--db-instance-identifier', 'homecare-prod-mysql')['DBInstances'][0]
    secret_arn = db['MasterUserSecret']['SecretArn']
    if POLICY in aws('iam', 'list-role-policies', '--role-name', ROLE)['PolicyNames']:
        raise SystemExit('Temporary policy already exists; inspect/remove it before rerunning.')
    # 2. 지정 RDS 관리자 비밀의 한시적 조회 권한 부여
    expiry = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=30)  # 관리자 비밀 조회 권한 유효기간 30분
    policy = {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Action': 'secretsmanager:GetSecretValue', 'Resource': secret_arn,
        'Condition': {'DateLessThan': {'aws:CurrentTime': expiry.strftime('%Y-%m-%dT%H:%M:%SZ')}}}]}
    aws('iam', 'put-role-policy', '--role-name', ROLE, '--policy-name', POLICY,
        '--policy-document', json.dumps(policy))
    try:
        print('Granted temporary read of the one RDS admin secret; expires in 30 minutes.', flush=True)
        time.sleep(10)  # IAM 권한 전파 대기 10초
        # 3. 초기화 스크립트·SQL을 EC2에 전달하고 SSM 실행
        commands = ['set -eu', 'install -d -m 0755 /usr/local/lib/homecare/sql']
        root = Path(__file__).resolve().parent
        for source, destination in (
                ('initialize-rds.py', '/usr/local/lib/homecare/initialize-rds.py'),
                ('sql/V001__initial_schema.sql', '/usr/local/lib/homecare/sql/V001__initial_schema.sql')):
            encoded = base64.b64encode((root / source).read_bytes()).decode()
            commands.append(f"printf '%s' '{encoded}' | base64 -d > {shlex.quote(destination)}")
            commands.append(f'chmod 0644 {shlex.quote(destination)}')
        commands.append('python3 /usr/local/lib/homecare/initialize-rds.py --master-secret-arn '
                        + shlex.quote(secret_arn) + ' --sql /usr/local/lib/homecare/sql/V001__initial_schema.sql')
        response = aws('ssm', 'send-command', '--instance-ids', INSTANCE,
                       '--document-name', 'AWS-RunShellScript', '--comment', 'Stage 4 RDS schema and app account initialization',
                       '--parameters', json.dumps({'commands': commands, 'executionTimeout': ['300']}))
        command_id = response['Command']['CommandId']
        print('SSM initialization command: ' + command_id, flush=True)
        time.sleep(5)
        # 4. SSM 실행 결과 확인(최대 90회, 대기 간격 5초)
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
        # 성공·실패와 관계없이 임시 관리자 조회 권한 해제
        aws('iam', 'delete-role-policy', '--role-name', ROLE, '--policy-name', POLICY)
        print('Removed temporary RDS administrator-secret read policy.', flush=True)


if __name__ == '__main__':
    main()
