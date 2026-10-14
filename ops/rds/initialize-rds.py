#!/usr/bin/env python3
"""EC2의 임시 관리자 권한으로 빈 RDS 스키마 초기화"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

TABLES = ('app_users', 'chat_conversations', 'chat_messages')
APP_HOST = '10.20.1.0/24'
# 확인된 V001 체크섬 전환만 허용(기존 이력 보존)
# 품목 범위 변경은 V004에서 별도 적용
V001_PREVIOUS_SHA256 = 'e937d617a27dcf4d94c2b2518dfd5d41bdcc7bdd46eec5e8db5f80b5be684475'
V001_CURRENT_SHA256 = 'e00118356e33685d9ea704df7e43404dd90b6e32872d2d90db95bbfb7269d0b7'


def aws(region, *arguments):
    result = subprocess.run(['aws', *arguments, '--region', region, '--output', 'json'],
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f'AWS {arguments[0]} {arguments[1]} failed; values withheld.')
    return json.loads(result.stdout)


def apply_schema(cursor, sql):
    """빈 DB의 V001 초기화와 확인된 기존 스키마 보존

    Args:
        cursor: 초기화 대상 DB의 SQL 실행 cursor.
        sql: V001 초기화 SQL 원문.

    Raises:
        SystemExit: 기존 테이블 구성 또는 V001 체크섬이 허용 범위와 다른 경우.
    """
    checksum = hashlib.sha256(sql.encode()).hexdigest()
    cursor.execute('SHOW TABLES')
    tables = {row[0] for row in cursor.fetchall()}
    # 기존 테이블이 있으면 허용 구성·체크섬 확인 후 보존
    if tables:
        if tables != {*TABLES, 'schema_migrations'}:
            raise SystemExit('Database is not empty or initialization is partial; inspect it manually.')
        cursor.execute('SELECT checksum FROM schema_migrations WHERE version = %s', ('V001',))
        row = cursor.fetchone()
        if row and row[0] == V001_PREVIOUS_SHA256 and checksum == V001_CURRENT_SHA256:
            print('Preserved previously applied V001 schema and checksum; apply subsequent migrations separately.')
            return
        if not row or row[0] != checksum:
            raise SystemExit('Initial SQL checksum mismatch; do not change an applied SQL file.')
        print('Preserved applied V001 schema; checksum matches.')
        return
    # 빈 DB에만 초기 SQL 적용 후 체크섬 기록
    for statement in sql.split(';'):
        if statement.strip():
            cursor.execute(statement)
    cursor.execute('CREATE TABLE schema_migrations (version VARCHAR(50) PRIMARY KEY, '
                   'checksum CHAR(64) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    cursor.execute('INSERT INTO schema_migrations (version, checksum) VALUES (%s, %s)', ('V001', checksum))
    print('Applied V001 schema: three application tables and initialization history.')


def validate_grants(grants):
    """앱 계정의 지정 테이블별 DML 권한 검증

    허용 목록과 다른 권한이 있으면 SystemExit를 발생시킵니다.
    """
    account = f'`homecare_app`@`{APP_HOST}`'
    expected = {f'GRANT USAGE ON *.* TO {account}'}
    expected.update(f'GRANT SELECT, INSERT, UPDATE, DELETE ON `project_db`.`{table}` TO {account}'
                    for table in TABLES)
    if set(grants) != expected:
        raise SystemExit('Unexpected app grants; inspect them manually.')


def main():
    """AWS 계정·접속 설정 확인 후 잠금을 확보한 RDS 초기화"""
    import pymysql

    # 1. 실행 인자 확인
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', default='ap-northeast-2')
    parser.add_argument('--master-secret-arn', required=True)
    parser.add_argument('--sql', type=Path, required=True)
    args = parser.parse_args()
    # 2. 대상 AWS 계정과 DB 접속 설정 확인
    identity = aws(args.region, 'sts', 'get-caller-identity')
    if identity['Account'] != '528215464049':
        raise SystemExit('Unexpected AWS account.')
    parameters = aws(args.region, 'ssm', 'get-parameters', '--names',
                     '/homecare/prod/SPRING_DATASOURCE_URL',
                     '/homecare/prod/SPRING_DATASOURCE_USERNAME',
                     '/homecare/prod/SPRING_DATASOURCE_PASSWORD', '--with-decryption')
    if parameters.get('InvalidParameters'):
        raise SystemExit('Missing database parameters.')
    values = {p['Name'].rsplit('/', 1)[1]: p['Value'] for p in parameters['Parameters']}
    match = re.fullmatch(r'jdbc:mysql://([a-z0-9.-]+):3306/project_db\?.+',
                         values['SPRING_DATASOURCE_URL'])
    if not match or values['SPRING_DATASOURCE_USERNAME'] != 'homecare_app':
        raise SystemExit('Unexpected database endpoint or application user.')
    settings = dict(host=match[1], port=3306, database='project_db', charset='utf8mb4',
                    ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True,
                    ssl_verify_identity=True, connect_timeout=10, read_timeout=30, write_timeout=30)
    # 3. 관리자 인증값 조회(임시 권한)
    secret = aws(args.region, 'secretsmanager', 'get-secret-value', '--secret-id', args.master_secret_arn)
    admin = json.loads(secret['SecretString'])
    # 4. DB 연결과 초기화 잠금 확보
    with pymysql.connect(**settings, user=admin['username'], password=admin['password'],
                         autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('homecare-initialize', 10)")
            if cursor.fetchone()[0] != 1:
                raise SystemExit('Another initialization is running.')
            try:
                apply_schema(cursor, args.sql.read_text())
                cursor.execute('SELECT Host FROM mysql.user WHERE User = %s', ('homecare_app',))
                hosts = {row[0] for row in cursor.fetchall()}
                if hosts and hosts != {APP_HOST}:
                    raise SystemExit('Unexpected existing app account host; inspect grants manually.')
                if not hosts:
                    cursor.execute('CREATE USER %s@%s IDENTIFIED BY %s REQUIRE SSL',
                                   ('homecare_app', APP_HOST, values['SPRING_DATASOURCE_PASSWORD']))
                for table in TABLES:
                    cursor.execute(f'GRANT SELECT, INSERT, UPDATE, DELETE ON project_db.{table} TO %s@%s',
                                   ('homecare_app', APP_HOST))
                cursor.execute('SELECT ssl_type FROM mysql.user WHERE User = %s AND Host = %s',
                               ('homecare_app', APP_HOST))
                if cursor.fetchone()[0] != 'ANY':
                    raise SystemExit('Application account must REQUIRE SSL; inspect it manually.')
            finally:
                cursor.execute("SELECT RELEASE_LOCK('homecare-initialize')")
    with pymysql.connect(**settings, user='homecare_app',
                         password=values['SPRING_DATASOURCE_PASSWORD']) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
            if not cursor.fetchone()[1]:
                raise SystemExit('Application connection is not encrypted.')
            cursor.execute('SHOW GRANTS')
            validate_grants([row[0] for row in cursor.fetchall()])
            for table in TABLES:
                cursor.execute(f'SELECT COUNT(*) FROM {table}')
            try:
                cursor.execute('CREATE TABLE stage4_forbidden (id BIGINT)')
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1142:
                    raise
            else:
                raise SystemExit('Application unexpectedly has CREATE permission; inspect database.')
    print('PASS: app account TLS identity verification, table reads and CREATE denial; no password rotated.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'RDS initialization failed ({type(error).__name__}); details withheld to protect credentials.') from None
