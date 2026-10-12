#!/usr/bin/env python3
"""Initialize the empty RDS schema on EC2 with temporary administrator access."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

TABLES = ('app_users', 'chat_conversations', 'chat_messages')
APP_HOST = '10.20.1.0/24'


def aws(region, *arguments):
    result = subprocess.run(['aws', *arguments, '--region', region, '--output', 'json'],
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f'AWS {arguments[0]} {arguments[1]} failed; values withheld.')
    return json.loads(result.stdout)


def apply_schema(cursor, sql):
    checksum = hashlib.sha256(sql.encode()).hexdigest()
    cursor.execute('SHOW TABLES')
    tables = {row[0] for row in cursor.fetchall()}
    if tables:
        if tables != {*TABLES, 'schema_migrations'}:
            raise SystemExit('Database is not empty or initialization is partial; inspect it manually.')
        cursor.execute('SELECT checksum FROM schema_migrations WHERE version = %s', ('V001',))
        row = cursor.fetchone()
        if not row or row[0] != checksum:
            raise SystemExit('Initial SQL checksum mismatch; do not change an applied SQL file.')
        print('Preserved applied V001 schema; checksum matches.')
        return
    for statement in sql.split(';'):
        if statement.strip():
            cursor.execute(statement)
    cursor.execute('CREATE TABLE schema_migrations (version VARCHAR(50) PRIMARY KEY, '
                   'checksum CHAR(64) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    cursor.execute('INSERT INTO schema_migrations (version, checksum) VALUES (%s, %s)', ('V001', checksum))
    print('Applied V001 schema: three application tables and initialization history.')


def validate_grants(grants):
    account = f'`homecare_app`@`{APP_HOST}`'
    expected = {f'GRANT USAGE ON *.* TO {account}'}
    expected.update(f'GRANT SELECT, INSERT, UPDATE, DELETE ON `project_db`.`{table}` TO {account}'
                    for table in TABLES)
    if set(grants) != expected:
        raise SystemExit('Unexpected app grants; inspect them manually.')


def main():
    import pymysql

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', default='ap-northeast-2')
    parser.add_argument('--master-secret-arn', required=True)
    parser.add_argument('--sql', type=Path, required=True)
    args = parser.parse_args()
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
    secret = aws(args.region, 'secretsmanager', 'get-secret-value', '--secret-id', args.master_secret_arn)
    admin = json.loads(secret['SecretString'])
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
