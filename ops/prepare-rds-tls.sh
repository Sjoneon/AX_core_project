#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y python3-pymysql openjdk-21-jre-headless
install -d -m 0700 /opt/homecare/config
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
curl -fsSL https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem \
    -o "$workdir/bundle.pem"

python3 - "$workdir" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

work = Path(sys.argv[1])
certificates = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----',
                          (work / 'bundle.pem').read_text(), re.DOTALL)
roots = []
for certificate in certificates:
    pem = work / 'certificate.pem'
    pem.write_text(certificate + '\n')
    info = subprocess.check_output(['openssl', 'x509', '-in', str(pem), '-noout',
                                    '-subject', '-issuer', '-nameopt', 'RFC2253'], text=True).splitlines()
    if info[0].removeprefix('subject=') != info[1].removeprefix('issuer='):
        continue
    subprocess.run(['openssl', 'verify', '-CAfile', str(pem), str(pem)], check=True)
    subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', f'rds-root-{len(roots)}',
                    '-file', str(pem), '-keystore', str(work / 'rds-truststore.p12'),
                    '-storetype', 'PKCS12', '-storepass', 'changeit'], check=True)
    roots.append(certificate)
if not roots:
    raise SystemExit('No verified RDS root CA found in the regional bundle.')
(work / 'rds-roots.pem').write_text('\n'.join(roots) + '\n')
print(f'Prepared {len(roots)} verified RDS root certificates; no intermediate CA imported.')
PY

install -m 0644 "$workdir/rds-roots.pem" /opt/homecare/config/rds-roots.pem
install -m 0644 "$workdir/rds-truststore.p12" /opt/homecare/config/rds-truststore.p12
