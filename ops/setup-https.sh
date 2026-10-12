#!/usr/bin/env bash
set -euo pipefail

release_dir=${1:?Pass the prepared HTTPS release directory}
if ! snap list certbot >/dev/null 2>&1; then
    snap install --classic certbot
fi
/snap/bin/certbot --version
install -d -m 0755 /opt/homecare/acme /etc/letsencrypt
install -m 0644 "$release_dir/bootstrap.conf" /opt/homecare/config/nginx-bootstrap.conf

compose=(docker compose --project-name homecare --env-file "$release_dir/images.env" -f "$release_dir/compose.prod.yaml")
configdir=$(mktemp -d)
trap 'rm -rf "$configdir"' EXIT
aws ecr get-login-password --region ap-northeast-2 |
    docker --config "$configdir" login --username AWS --password-stdin \
        528215464049.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null
DOCKER_CONFIG="$configdir" "${compose[@]}" pull
"${compose[@]}" -f "$release_dir/compose.acme.yaml" up -d --remove-orphans --wait --wait-timeout 180

/snap/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
    --webroot --webroot-path /opt/homecare/acme --ip-address 43.202.80.116 \
    --preferred-profile shortlived --cert-name homecare-api
bash "$release_dir/start-service.sh" "$release_dir"
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
install -m 0755 "$release_dir/renew-nginx.sh" /etc/letsencrypt/renewal-hooks/deploy/homecare-nginx
systemctl enable --now snap.certbot.renew.timer
/snap/bin/certbot renew --cert-name homecare-api --dry-run --run-deploy-hooks
systemctl is-enabled snap.certbot.renew.timer
systemctl is-active snap.certbot.renew.timer
