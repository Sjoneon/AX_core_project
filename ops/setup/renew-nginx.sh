#!/usr/bin/env bash
set -euo pipefail

if [[ ${RENEWED_LINEAGE:-/etc/letsencrypt/live/homecare-api} != /etc/letsencrypt/live/homecare-api ]]; then
    exit 0
fi
docker compose --project-name homecare --env-file /opt/homecare/current/images.env \
    -f /opt/homecare/current/compose.prod.yaml exec -T nginx nginx -t
docker compose --project-name homecare --env-file /opt/homecare/current/images.env \
    -f /opt/homecare/current/compose.prod.yaml exec -T nginx nginx -s reload
