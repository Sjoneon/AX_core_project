#!/usr/bin/env bash
set -euo pipefail

release_dir=${1:?Pass the release directory containing compose.prod.yaml and images.env}
test -r "$release_dir/compose.prod.yaml"
test -r "$release_dir/images.env"
test -r /opt/homecare/config/rds-truststore.p12

python3 /usr/local/lib/homecare/write-runtime-env.py
compose=(docker compose --project-name homecare --env-file "$release_dir/images.env" -f "$release_dir/compose.prod.yaml")
"${compose[@]}" config --quiet

configdir=$(mktemp -d)
trap 'rm -rf "$configdir"' EXIT
aws ecr get-login-password --region ap-northeast-2 |
    docker --config "$configdir" login --username AWS --password-stdin \
        528215464049.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null
DOCKER_CONFIG="$configdir" "${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans --wait --wait-timeout 180
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload
curl -fsS --max-time 5 http://127.0.0.1/actuator/health
printf '\nService is healthy through Nginx.\n'
ln -sfn "$release_dir" /opt/homecare/current
