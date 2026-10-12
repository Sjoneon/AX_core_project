#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl gnupg unzip python3
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

. /etc/os-release
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

install -m 0755 -d /etc/docker
python3 - <<'PY'
import json
from pathlib import Path
p = Path('/etc/docker/daemon.json')
settings = json.loads(p.read_text()) if p.exists() else {}
settings.update({'log-driver': 'json-file', 'log-opts': {'max-size': '10m', 'max-file': '3'}})
p.write_text(json.dumps(settings, indent=2) + '\n')
PY
systemctl enable --now docker.service containerd.service
systemctl restart docker.service

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
curl -fsSL https://awscli.amazonaws.com/v2/install.sh -o "$workdir/install-aws-cli.sh"
bash "$workdir/install-aws-cli.sh" --system

install -d -m 0700 /opt/homecare /opt/homecare/config /opt/homecare/releases
install -d -m 0755 /usr/local/lib/homecare
docker --version
docker compose version
aws --version
systemctl is-enabled docker.service
systemctl is-active docker.service
