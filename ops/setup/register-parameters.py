#!/usr/bin/env python3
"""누락된 운영 SecureString 등록(기존 값 보존, 비밀값 출력 제외)"""
import argparse
import base64
import json
import os
import secrets
import shlex
import subprocess
import tempfile
from pathlib import Path


def aws(region, *arguments):
    result = subprocess.run(['aws', *arguments, '--region', region, '--output', 'json'],
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f'AWS {arguments[0]} {arguments[1]} failed; check credentials and permissions.')
    return json.loads(result.stdout)


def main():
    """AWS 계정 확인 후 누락된 SecureString만 등록"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', default='ap-northeast-2')
    parser.add_argument('--prefix', default='/homecare/prod')
    parser.add_argument('--account-id', default='528215464049')
    parser.add_argument('--env-file', type=Path, default=Path(__file__).resolve().parents[2] / 'backend/.env')
    args = parser.parse_args()
    # 대상 AWS 계정과 기존 파라미터 확인
    if aws(args.region, 'sts', 'get-caller-identity')['Account'] != args.account_id:
        raise SystemExit('AWS account does not match the expected account.')
    listed = aws(args.region, 'ssm', 'describe-parameters', '--parameter-filters',
                 f'Key=Name,Option=BeginsWith,Values={args.prefix}/')
    existing = {parameter['Name']: parameter['Type'] for parameter in listed['Parameters']}
    key_name = f'{args.prefix}/OPENAI_API_KEY'
    openai_key = None
    # OPENAI_API_KEY가 없으면 로컬 환경 파일에서 등록값 조회
    if key_name not in existing:
        for line in args.env_file.read_text().splitlines():
            if line.strip().startswith('OPENAI_API_KEY='):
                tokens = shlex.split(line.split('=', 1)[1], comments=True)
                openai_key = tokens[0] if len(tokens) == 1 else None
                break
        if not openai_key or not openai_key.startswith('sk-'):
            raise SystemExit('Set a real OPENAI_API_KEY in the local backend .env file before registration.')
    values = {
        'OPENAI_API_KEY': lambda: openai_key,
        'JWT_SECRET': lambda: base64.b64encode(secrets.token_bytes(32)).decode(),
        'SPRING_DATASOURCE_PASSWORD': lambda: secrets.token_urlsafe(32),
    }
    # 기존 SecureString 보존, 누락된 값만 생성·등록
    for key, generate in values.items():
        name = f'{args.prefix}/{key}'
        if name in existing:
            if existing[name] != 'SecureString':
                raise SystemExit(f'{name} exists but is not SecureString; correct it before proceeding.')
            print(f'Preserved existing SecureString: {name}')
            continue
        payload = {'Name': name, 'Type': 'SecureString', 'Tier': 'Standard',
                   'Value': generate(), 'Overwrite': False}
        # 비밀값을 임시 파일로 전달하고 등록 후 삭제
        fd, temporary = tempfile.mkstemp(prefix='homecare-parameter-', suffix='.json')
        try:
            with os.fdopen(fd, 'w') as stream:
                json.dump(payload, stream)
            aws(args.region, 'ssm', 'put-parameter', '--cli-input-json', 'file://' + temporary)
        finally:
            os.unlink(temporary)
        print(f'Created SecureString: {name}')


if __name__ == '__main__':
    main()
