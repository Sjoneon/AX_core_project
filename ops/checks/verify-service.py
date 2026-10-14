#!/usr/bin/env python3
"""운영 컨테이너 상태·백엔드 교체·API 데이터 보존 검증(MCP 릴리스는 mcp 포함)"""
import json
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Service verification command failed; details withheld.')
    return result.stdout.strip()


def request(path, payload=None, token=None, method=None):
    """인증값이 제공되면 Bearer 헤더를 포함한 API 응답 조회(204는 None 반환)"""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request('https://43.202.80.116' + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response) if response.status != 204 else None


def wait_for_health():
    """프록시 health 확인(최대 60회, 실패 시 2초 간격 재시도)"""
    for _ in range(60):
        try:
            if request('/actuator/health')['status'] == 'UP':
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(2)
    raise RuntimeError('Proxy health timeout; inspect private service logs on EC2.')


def main():
    import pymysql

    # 현재 릴리스의 Compose 구성과 컨테이너 상태 확인
    release = Path('/opt/homecare/current').resolve()
    files = ['-f', str(release / 'compose.prod.yaml')]
    if (release / 'compose.mcp.yaml').exists():
        files += ['-f', str(release / 'compose.mcp.yaml')]
    compose = ['docker', 'compose', '--project-name', 'homecare', '--env-file', str(release / 'images.env'), *files]
    expected = {'backend', 'nginx'} | ({'mcp'} if len(files) == 4 else set())
    ids = command(*compose, 'ps', '-q').splitlines()
    assert len(ids) == len(expected)
    containers = json.loads(command('docker', 'inspect', *ids))
    services = set()
    for container in containers:
        service = container['Config']['Labels']['com.docker.compose.service']
        services.add(service)
        assert container['State']['Health']['Status'] == 'healthy'
        assert container['HostConfig']['RestartPolicy']['Name'] == 'unless-stopped'
        assert container['HostConfig']['ReadonlyRootfs']
        assert container['HostConfig']['LogConfig']['Type'] == 'awslogs'
        assert container['HostConfig']['LogConfig']['Config']['cache-max-size'] == '10m'
        if service == 'backend':
            assert not container['HostConfig']['PortBindings']
            assert container['Config']['User'] == '10001:10001'
            assert container['HostConfig']['Memory'] == 1073741824
        elif service == 'mcp':
            # MCP 내부 통신·비루트 실행·capability 제거·메모리 상한 256 MiB 검증
            assert not container['HostConfig']['PortBindings']
            assert container['Config']['User'] == '10001:10001'
            assert container['HostConfig']['CapDrop'] == ['ALL']
            assert 'no-new-privileges:true' in container['HostConfig']['SecurityOpt']
            assert container['HostConfig']['Memory'] == 268435456
        else:
            assert service == 'nginx'
            assert set(container['HostConfig']['PortBindings']) == {'80/tcp', '443/tcp'}
            assert container['HostConfig']['Memory'] == 134217728
    assert services == expected
    # 공개 API 인증과 내부·관리 경로 차단 확인
    for path, status in (('/api/conversations', 401), ('/', 404), ('/actuator/env', 404), ('/mcp', 404),
                         ('/api/tools/lookup_model', 404)):
        try:
            request(path)
        except urllib.error.HTTPError as error:
            assert error.code == status
        else:
            raise RuntimeError('Unexpected proxy route response.')
    values = dict(line.split('=', 1) for line in Path('/opt/homecare/config/runtime.env').read_text().splitlines())
    host = re.fullmatch(r'jdbc:mysql://([a-z0-9.-]+):3306/project_db\?.+',
                        values['SPRING_DATASOURCE_URL'])[1]
    # 이번 실행만의 임시 회원·상담 생성과 데이터 보존 검증
    email = 'service-verify-' + uuid.uuid4().hex + '@example.com'
    password = secrets.token_urlsafe(24)
    with pymysql.connect(host=host, database='project_db', user=values['SPRING_DATASOURCE_USERNAME'],
                         password=values['SPRING_DATASOURCE_PASSWORD'], charset='utf8mb4', autocommit=True,
                         ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True,
                         ssl_verify_identity=True) as connection:
        try:
            request('/api/auth/signup', {'email': email, 'password': password})
            token = request('/api/auth/login', {'email': email, 'password': password})['accessToken']
            chat = request('/api/chat', {'message': '배포 연결 확인 🧺'}, token=token)
            assert '제품 카테고리' in chat['reply'] and not chat['sources']
            conversation_id = chat['conversationId']
            before = request(f'/api/conversations/{conversation_id}', token=token)
            assert len(before['messages']) == 2
            previous = command(*compose, 'ps', '-q', 'backend')
            command(*compose, 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '180', 'backend')
            assert command(*compose, 'ps', '-q', 'backend') != previous
            command(*compose, 'exec', '-T', 'nginx', 'nginx', '-t')
            command(*compose, 'exec', '-T', 'nginx', 'nginx', '-s', 'reload')
            wait_for_health()
            assert request(f'/api/conversations/{conversation_id}', token=token) == before
            assert request('/api/auth/login', {'email': email, 'password': password})['email'] == email
            request(f'/api/conversations/{conversation_id}', token=token, method='DELETE')
            assert request('/api/conversations', token=token) == []
            print(f'PASS: containers {sorted(expected)}; backend and mcp ports unpublished; memory/restart/log limits; '
                  'proxy routes, blocked /mcp and /api/tools, auth/chat/read/delete; backend replacement preserves RDS data and JWT.')
        finally:
            with connection.cursor() as cursor:
                cursor.execute('SELECT id FROM app_users WHERE email = %s', (email,))
                row = cursor.fetchone()
                if row:
                    cursor.execute('DELETE m FROM chat_messages m JOIN chat_conversations c '
                                   'ON c.id = m.conversation_id WHERE c.user_id = %s', (row[0],))
                    cursor.execute('DELETE FROM chat_conversations WHERE user_id = %s', (row[0],))
                    cursor.execute('DELETE FROM app_users WHERE id = %s', (row[0],))
            print('Removed only this verification user and its records; the two service containers remain running.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'Service verification failed ({type(error).__name__}); details withheld.') from None
