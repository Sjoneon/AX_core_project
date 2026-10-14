#!/usr/bin/env bash
set -euo pipefail

# 1. Certbot과 HTTP 인증 경로 준비
release_dir=${1:?Pass the prepared HTTPS release directory}
if ! snap list certbot >/dev/null 2>&1; then
    snap install --classic certbot
fi
/snap/bin/certbot --version
install -d -m 0755 /opt/homecare/acme /etc/letsencrypt
install -m 0644 "$release_dir/bootstrap.conf" /opt/homecare/config/nginx-bootstrap.conf

# 2. 초기 HTTP 인증용 Compose 구성 기동
compose=(docker compose --project-name homecare --env-file "$release_dir/images.env" -f "$release_dir/compose.prod.yaml")
configdir=$(mktemp -d)
trap 'rm -rf "$configdir"' EXIT
aws ecr get-login-password --region ap-northeast-2 |
    docker --config "$configdir" login --username AWS --password-stdin \
        528215464049.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null
DOCKER_CONFIG="$configdir" "${compose[@]}" pull
"${compose[@]}" -f "$release_dir/compose.acme.yaml" up -d --remove-orphans --wait --wait-timeout 180

# 3. IP 인증서 발급 후 HTTPS 서비스 기동
/snap/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
    --webroot --webroot-path /opt/homecare/acme --ip-address 43.202.80.116 \
    --preferred-profile shortlived --cert-name homecare-api
bash "$release_dir/start-service.sh" "$release_dir"
# 4. 갱신 hook·timer 등록과 dry-run 확인
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
install -m 0755 "$release_dir/renew-nginx.sh" /etc/letsencrypt/renewal-hooks/deploy/homecare-nginx
systemctl enable --now snap.certbot.renew.timer
/snap/bin/certbot renew --cert-name homecare-api --dry-run --run-deploy-hooks
systemctl is-enabled snap.certbot.renew.timer
systemctl is-active snap.certbot.renew.timer
