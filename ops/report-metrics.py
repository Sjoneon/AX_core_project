#!/usr/bin/env python3
"""Publish host health metrics without reading app secrets or creating containers."""
import argparse
import json
import shutil
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def memory_used_percent(text):
    values = {line.split(':', 1)[0]: int(line.split()[1]) for line in text.splitlines()}
    return 100 * (1 - values['MemAvailable'] / values['MemTotal'])


def certificate_hours(text, now):
    expiry = datetime.strptime(text.strip().removeprefix('notAfter='), '%b %d %H:%M:%S %Y GMT')
    return (expiry.replace(tzinfo=timezone.utc) - now).total_seconds() / 3600


def collect():
    disk = shutil.disk_usage('/opt/homecare')
    metrics = {
        'MemoryUsedPercent': (memory_used_percent(Path('/proc/meminfo').read_text()), 'Percent'),
        'DiskUsedPercent': (100 * disk.used / disk.total, 'Percent'),
    }
    try:
        with urllib.request.urlopen('https://43.202.80.116/actuator/health', timeout=5) as response:
            healthy = int(json.load(response)['status'] == 'UP')
    except (OSError, ValueError, KeyError):
        healthy = 0
    metrics['ServiceHealthy'] = (healthy, 'Count')
    result = subprocess.run(['openssl', 'x509', '-in', '/etc/letsencrypt/live/homecare-api/fullchain.pem',
                             '-noout', '-enddate'], capture_output=True, text=True, timeout=10)
    try:
        hours = certificate_hours(result.stdout, datetime.now(timezone.utc)) if result.returncode == 0 else 0
    except ValueError:
        hours = 0
    metrics['CertificateRemainingHours'] = (hours, 'None')
    timer = subprocess.run(['systemctl', 'is-active', '--quiet', 'snap.certbot.renew.timer'], timeout=10)
    metrics['CertbotTimerActive'] = (int(timer.returncode == 0), 'Count')
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance-id', required=True)
    args = parser.parse_args()
    data = [{'MetricName': name, 'Value': value, 'Unit': unit,
             'Dimensions': [{'Name': 'InstanceId', 'Value': args.instance_id}]} for name, (value, unit) in collect().items()]
    result = subprocess.run(['aws', 'cloudwatch', 'put-metric-data', '--region', 'ap-northeast-2',
                             '--namespace', 'homecare/prod', '--metric-data', json.dumps(data)],
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise SystemExit('Failed to publish metrics; check the instance role and AWS connectivity.')
    print('Published five host health metrics; no app settings or request data were read.')


if __name__ == '__main__':
    main()
