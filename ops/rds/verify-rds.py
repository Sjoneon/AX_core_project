#!/usr/bin/env python3
"""Verify the app against RDS on EC2; clean up only this run's container and data."""
import argparse
import json
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def docker(*arguments):
    result = subprocess.run(['docker', *arguments], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Docker verification command failed; output withheld.')
    return result.stdout.strip()


def request(path, payload=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request('http://127.0.0.1:18084' + path, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def wait_for_health():
    for _ in range(60):
        try:
            if request('/actuator/health')['status'] == 'UP':
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(2)
    raise RuntimeError('Application health failed; inspect private container logs on EC2.')


def main():
    import pymysql

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    values = dict(line.split('=', 1) for line in Path('/opt/homecare/config/runtime.env').read_text().splitlines())
    host = re.fullmatch(r'jdbc:mysql://([a-z0-9.-]+):3306/project_db\?.+',
                        values['SPRING_DATASOURCE_URL'])[1]
    settings = dict(host=host, database='project_db', user=values['SPRING_DATASOURCE_USERNAME'],
                    password=values['SPRING_DATASOURCE_PASSWORD'], charset='utf8mb4',
                    ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True,
                    ssl_verify_identity=True, connect_timeout=10, autocommit=True)
    run_id = uuid.uuid4().hex
    name = 'homecare-rds-verify-' + run_id[:12]
    email = 'rds-verify-' + run_id + '@example.com'
    password = secrets.token_urlsafe(24)
    user_id = None
    with pymysql.connect(**settings) as connection:
        try:
            docker('run', '-d', '--name', name, '--memory', '768m',
                   '-p', '127.0.0.1:18084:8080', '--env-file', '/opt/homecare/config/runtime.env',
                   '-e', 'OPENAI_API_KEY=stage4-unused-key',
                   '-e', 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65',
                   '-v', '/opt/homecare/config/rds-truststore.p12:/app/config/rds-truststore.p12:ro',
                   args.image)
            wait_for_health()
            request('/api/auth/signup', {'email': email, 'password': password})
            token = request('/api/auth/login', {'email': email, 'password': password})['accessToken']
            with connection.cursor() as cursor:
                cursor.execute('SELECT id FROM app_users WHERE email = %s', (email,))
                user_id = cursor.fetchone()[0]
                cursor.execute("INSERT INTO chat_conversations (user_id,title,category,created_at,updated_at) "
                               "VALUES (%s,%s,'WASHER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", (user_id, 'TLS·재시작 검증'))
                conversation_id = cursor.lastrowid
                source = {'documentId': 'stage4', 'title': '검증', 'category': 'WASHER',
                          'version': '1', 'file': 'verification', 'content': '한글·이모지 확인 🧺'}
                cursor.execute("INSERT INTO chat_messages (conversation_id,role,content,sources,created_at) "
                               "VALUES (%s,'assistant',%s,%s,UTC_TIMESTAMP(6))",
                               (conversation_id, '재시작 후에도 보존 🧺', json.dumps([source])))
                cursor.execute('UPDATE chat_conversations SET title = %s WHERE id = %s',
                               ('TLS·상담 보존 검증', conversation_id))
            before = request(f'/api/conversations/{conversation_id}', token=token)
            assert before['messages'][0]['sources'][0] == source
            docker('restart', name)
            wait_for_health()
            assert request(f'/api/conversations/{conversation_id}', token=token) == before
            assert request('/api/auth/login', {'email': email, 'password': password})['email'] == email
            assert any(row['id'] == conversation_id for row in request('/api/conversations', token=token))
            print('PASS: Hibernate validate, JDBC VERIFY_IDENTITY, signup/login, Korean/emoji/source reads, restart persistence and JWT continuity.')
        finally:
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
            with connection.cursor() as cursor:
                if user_id is None:
                    cursor.execute('SELECT id FROM app_users WHERE email = %s', (email,))
                    row = cursor.fetchone()
                    user_id = row[0] if row else None
                if user_id is not None:
                    cursor.execute('DELETE m FROM chat_messages m JOIN chat_conversations c '
                                   'ON c.id = m.conversation_id WHERE c.user_id = %s', (user_id,))
                    cursor.execute('DELETE FROM chat_conversations WHERE user_id = %s', (user_id,))
                    cursor.execute('DELETE FROM app_users WHERE id = %s', (user_id,))
            print('Removed this verification container and synthetic user/conversation/message data.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'RDS verification failed ({type(error).__name__}); values withheld.') from None
