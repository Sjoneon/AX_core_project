#!/usr/bin/env python3
"""EC2 앱 파라미터 조회와 제한된 권한의 Docker 환경 파일 원자적 교체"""
import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

KEYS = (
    'OPENAI_API_KEY', 'OPENAI_MODEL', 'JWT_SECRET', 'JWT_EXPIRATION_MS',
    'SERVER_ADDRESS', 'SERVER_PORT', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'SPRING_JPA_HIBERNATE_DDL_AUTO',
)


def main():
    """앱 파라미터 검증 후 runtime.env 저장(비밀값 출력 제외)"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', default='ap-northeast-2')
    parser.add_argument('--prefix', default='/homecare/prod')
    parser.add_argument('--output', default='/opt/homecare/config/runtime.env')
    args = parser.parse_args()
    # SSM 앱 파라미터 조회와 누락 항목 확인
    names = [f'{args.prefix}/{key}' for key in KEYS]
    result = subprocess.run(
        ['aws', 'ssm', 'get-parameters', '--region', args.region, '--names', *names,
         '--with-decryption', '--output', 'json'], capture_output=True, text=True,
    )
    if result.returncode:
        raise SystemExit('Failed to read app parameters; check the instance IAM role.')
    response = json.loads(result.stdout)
    if response.get('InvalidParameters'):
        raise SystemExit('Missing app parameters: ' + ', '.join(response['InvalidParameters']))
    values = {parameter['Name']: parameter['Value'] for parameter in response['Parameters']}
    # 비어 있는 값과 줄 주입 문자가 있으면 저장 거부
    lines = []
    for name, key in zip(names, KEYS):
        value = values[name]
        if not value or any(character in value for character in ('\r', '\n', '\x00')):
            raise SystemExit(f'Invalid value for {key}; values must be nonempty single lines.')
        lines.append(f'{key}={value}\n')
    output = Path(args.output)
    # 대상 디렉터리에 임시 파일 작성 후 원자적 교체
    fd, temporary = tempfile.mkstemp(prefix='.runtime-', dir=output.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.writelines(lines)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(f'Prepared {len(KEYS)} app settings at {output}; values are not printed.')


if __name__ == '__main__':
    main()
