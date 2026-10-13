#!/usr/bin/env python3
"""Create, check, and remove only an isolated backup verification fixture on EC2."""
import argparse
import importlib.util
import json
import re
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path


def main():
    import pymysql

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['seed', 'verify', 'cleanup'])
    parser.add_argument('--record', type=Path, required=True)
    parser.add_argument('--restore-host')
    args = parser.parse_args()
    values = dict(line.split('=', 1) for line in Path('/opt/homecare/config/runtime.env').read_text().splitlines())
    source_host = re.fullmatch(r'jdbc:mysql://([a-z0-9.-]+):3306/project_db\?.+', values['SPRING_DATASOURCE_URL'])[1]
    settings = dict(database='project_db', user=values['SPRING_DATASOURCE_USERNAME'],
                    password=values['SPRING_DATASOURCE_PASSWORD'], charset='utf8mb4', autocommit=True,
                    ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True, ssl_verify_identity=True,
                    connect_timeout=10, read_timeout=15, write_timeout=15)
    if args.action == 'seed':
        if args.record.exists():
            raise SystemExit('A verification record already exists; clean it up first.')
        spec = importlib.util.spec_from_file_location('service', '/opt/homecare/current/verify-service.py')
        service = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(service)
        record = {'email': 'backup-verify-' + uuid.uuid4().hex + '@example.com',
                  'message': '백업 복구 확인 🧺 ' + uuid.uuid4().hex}
        # Save the identity before signup so even a partial seed can be cleaned up.
        args.record.write_text(json.dumps(record))
        args.record.chmod(0o600)
        password = secrets.token_urlsafe(24)
        service.request('/api/auth/signup', {'email': record['email'], 'password': password})
        token = service.request('/api/auth/login', {'email': record['email'], 'password': password})['accessToken']
        result = service.request('/api/chat', {'message': record['message']}, token=token)
        record.update(conversation_id=result['conversationId'], committed_at=datetime.now(timezone.utc).isoformat())
        args.record.write_text(json.dumps(record))
        print('Fixture committed at ' + record['committed_at'] + '; no OpenAI request was made.')
        return

    record = json.loads(args.record.read_text())
    if not re.fullmatch(r'backup-verify-[0-9a-f]{32}@example\.com', record['email']):
        raise SystemExit('Refusing to touch an unrecognized verification identity.')
    host = source_host
    if args.action == 'verify':
        host = args.restore_host or ''
        if host == source_host or not re.fullmatch(r'homecare-prod-restore-check-[a-z0-9-]+\.[a-z0-9.-]+\.rds\.amazonaws\.com', host):
            raise SystemExit('Verification requires a distinct private restore-check endpoint.')
    with pymysql.connect(host=host, **settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute('SELECT id FROM app_users WHERE email=%s', (record['email'],))
            row = cursor.fetchone()
            if args.action == 'cleanup':
                if row:
                    cursor.execute('DELETE m FROM chat_messages m JOIN chat_conversations c '
                                   'ON c.id=m.conversation_id WHERE c.user_id=%s', (row[0],))
                    cursor.execute('DELETE FROM chat_conversations WHERE user_id=%s', (row[0],))
                    cursor.execute('DELETE FROM app_users WHERE id=%s', (row[0],))
                args.record.unlink()
                print('Removed only the synthetic backup verification user and its source records.')
                return
            assert row, 'Restored test user is missing'
            cursor.execute('SELECT user_id FROM chat_conversations WHERE id=%s', (record['conversation_id'],))
            assert cursor.fetchone() == row, 'Restored conversation ownership differs'
            cursor.execute('SELECT role,content FROM chat_messages WHERE conversation_id=%s ORDER BY id',
                           (record['conversation_id'],))
            messages = cursor.fetchall()
            assert len(messages) == 2 and any(content == record['message'] for _, content in messages)
            assert any('제품 카테고리' in content for _, content in messages)
            cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
            assert cursor.fetchone()[1], 'Restored connection is not encrypted'
    print('PASS: restored user, conversation ownership, Korean/emoji messages and verified DB TLS.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'Backup data verification failed ({type(error).__name__}); private details withheld.') from None
