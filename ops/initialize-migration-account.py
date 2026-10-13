#!/usr/bin/env python3
"""Provision a database-scoped migrator with temporary administrator access, once."""
import argparse
import importlib.util
import json
import re
from pathlib import Path


def validate_migration_grants(grants):
    account = '`homecare_migrator`@`10.20.1.0/24`'
    usage = f'GRANT USAGE ON *.* TO {account}'
    expected = {'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'INDEX', 'REFERENCES'}
    if len(grants) != 2 or usage not in grants:
        raise ValueError('Unexpected global migration privileges')
    scoped = next(grant for grant in grants if grant != usage)
    suffix = f' ON `project_db`.* TO {account} WITH GRANT OPTION'
    if not scoped.startswith('GRANT ') or not scoped.endswith(suffix):
        raise ValueError('Unexpected migration grant scope')
    if set(scoped[6:-len(suffix)].split(', ')) != expected:
        raise ValueError('Unexpected migration privileges')


def main():
    import pymysql

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--master-secret-arn', required=True)
    parser.add_argument('--sql', type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('initializer', Path(__file__).with_name('initialize-rds.py'))
    initializer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(initializer)
    aws = lambda *arguments: initializer.aws('ap-northeast-2', *arguments)
    if aws('sts', 'get-caller-identity')['Account'] != '528215464049':
        raise ValueError('Unexpected AWS account')
    response = aws('ssm', 'get-parameters', '--names', '/homecare/prod/SPRING_DATASOURCE_URL',
                   '/homecare/prod/MIGRATION_DATASOURCE_PASSWORD', '--with-decryption')
    if response.get('InvalidParameters'):
        raise ValueError('Missing migration configuration')
    values = {p['Name'].rsplit('/', 1)[-1]: p['Value'] for p in response['Parameters']}
    match = re.fullmatch(r'jdbc:mysql://([a-z0-9.-]+\.rds\.amazonaws\.com):3306/project_db\?.+', values['SPRING_DATASOURCE_URL'])
    if not match:
        raise ValueError('Unexpected RDS URL')
    admin = json.loads(aws('secretsmanager', 'get-secret-value', '--secret-id', args.master_secret_arn)['SecretString'])
    with pymysql.connect(host=match[1], port=3306, database='project_db', charset='utf8mb4',
                         user=admin['username'], password=admin['password'], autocommit=True,
                         ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True,
                         ssl_verify_identity=True, connect_timeout=10, read_timeout=30, write_timeout=30) as connection:
        with connection.cursor() as cursor:
            # Must match the already approved, applied initial schema; never runs new DDL for app tables.
            cursor.execute('SHOW TABLES')
            if {row[0] for row in cursor.fetchall()} != {*initializer.TABLES, 'schema_migrations'}:
                raise ValueError('Expected initialized V001 database before Flyway onboarding')
            initializer.apply_schema(cursor, args.sql.read_text())
            cursor.execute('SHOW GRANTS FOR %s@%s', ('homecare_app', initializer.APP_HOST))
            initializer.validate_grants([row[0] for row in cursor.fetchall()])
            cursor.execute('SELECT Host FROM mysql.user WHERE User=%s', ('homecare_migrator',))
            hosts = {row[0] for row in cursor.fetchall()}
            if hosts and hosts != {initializer.APP_HOST}:
                raise ValueError('Unexpected existing migration account')
            if not hosts:
                cursor.execute('CREATE USER %s@%s IDENTIFIED BY %s REQUIRE SSL',
                               ('homecare_migrator', initializer.APP_HOST, values['MIGRATION_DATASOURCE_PASSWORD']))
            cursor.execute('GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES '
                           'ON project_db.* TO %s@%s WITH GRANT OPTION', ('homecare_migrator', initializer.APP_HOST))
            cursor.execute('SELECT ssl_type FROM mysql.user WHERE User=%s AND Host=%s',
                           ('homecare_migrator', initializer.APP_HOST))
            if cursor.fetchone()[0] != 'ANY':
                raise ValueError('Migration account must require TLS')
    # Check the stored credential actually logs in (reruns never rotate a password).
    with pymysql.connect(host=match[1], port=3306, database='project_db', charset='utf8mb4',
                         user='homecare_migrator', password=values['MIGRATION_DATASOURCE_PASSWORD'],
                         ssl_ca='/opt/homecare/config/rds-roots.pem', ssl_verify_cert=True,
                         ssl_verify_identity=True, connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            cursor.execute('SHOW GRANTS')
            grants = [row[0] for row in cursor.fetchall()]
            validate_migration_grants(grants)
    print('PASS: separate migration account, database-scoped DDL/grants, TLS; app grants unchanged.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(f'Migration account setup failed ({type(error).__name__}); values withheld.') from None
