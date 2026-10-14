#!/usr/bin/env bash
set -euo pipefail

# 1. 릴리스 파일·TLS 설정 사전 확인
release_dir=${1:?Pass the release directory containing compose.prod.yaml and images.env}
test -r "$release_dir/compose.prod.yaml"
test -r "$release_dir/images.env"
test -r /opt/homecare/config/rds-truststore.p12
test -r /etc/letsencrypt/live/homecare-api/fullchain.pem
test -r /etc/letsencrypt/live/homecare-api/privkey.pem

# 2. 운영 환경 파일 생성과 Compose 구성 검증
python3 /usr/local/lib/homecare/write-runtime-env.py
compose=(docker compose --project-name homecare --env-file "$release_dir/images.env" -f "$release_dir/compose.prod.yaml")
if [[ -r "$release_dir/compose.mcp.yaml" ]]; then
    compose+=(-f "$release_dir/compose.mcp.yaml")
fi
"${compose[@]}" config --quiet

# 3. 임시 Docker 인증으로 이미지 조회·서비스 기동
configdir=$(mktemp -d)
trap 'rm -rf "$configdir"' EXIT
aws ecr get-login-password --region ap-northeast-2 |
    docker --config "$configdir" login --username AWS --password-stdin \
        528215464049.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null
DOCKER_CONFIG="$configdir" "${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans --wait --wait-timeout 180
# 4. Nginx 설정과 외부 health 확인 후 현재 릴리스 지정
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload
curl -fsS --max-time 5 https://43.202.80.116/actuator/health
printf '\nService is healthy through Nginx.\n'
ln -sfn "$release_dir" /opt/homecare/current
