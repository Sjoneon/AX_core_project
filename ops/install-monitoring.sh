#!/usr/bin/env bash
set -euo pipefail

instance_id=${1:?Pass the EC2 instance ID}
[[ "$instance_id" =~ ^i-[a-f0-9]+$ ]]
test "$(id -u)" = 0
source_dir=$(cd "$(dirname "$0")" && pwd)
install -d -m 755 /usr/local/lib/homecare
install -m 755 "$source_dir/report-metrics.py" /usr/local/lib/homecare/report-metrics.py
cat > /etc/systemd/system/homecare-metrics.service <<EOF
[Unit]
Description=Homecare host and HTTPS health metrics
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /usr/local/lib/homecare/report-metrics.py --instance-id $instance_id
User=root
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
TimeoutStartSec=60
EOF
cat > /etc/systemd/system/homecare-metrics.timer <<'EOF'
[Unit]
Description=Report Homecare health every minute

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min
AccuracySec=5s
Unit=homecare-metrics.service

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now homecare-metrics.timer
systemctl start homecare-metrics.service
systemctl is-active --quiet homecare-metrics.timer
printf 'Host monitoring is installed; the two application containers were not restarted.\n'
