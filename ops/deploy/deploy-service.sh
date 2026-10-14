#!/usr/bin/env bash
set -euo pipefail
umask 077

release_dir=${1:?Pass the extracted release directory}
test "$(id -u)" = 0
test -r "$release_dir/compose.prod.yaml"
test -r "$release_dir/compose.mcp.yaml"
test -r "$release_dir/images.env"
test -r "$release_dir/migrate-database.py"
test -r /opt/homecare/config/rds-truststore.p12
test -r /etc/letsencrypt/live/homecare-api/fullchain.pem
test -r /etc/letsencrypt/live/homecare-api/privkey.pem

exec 9>/opt/homecare/deploy.lock
flock -w 600 9
previous=''
if [[ -L /opt/homecare/current ]]; then
    previous=$(readlink -f /opt/homecare/current)
fi
configdir=$(mktemp -d)
export DOCKER_CONFIG=$configdir
changed=0

compose() {
    local directory=$1
    shift
    local files=(-f "$directory/compose.prod.yaml")
    # Releases with the MCP container carry compose.mcp.yaml; restoring an older release restores its two containers.
    if [[ -r "$directory/compose.mcp.yaml" ]]; then
        files+=(-f "$directory/compose.mcp.yaml")
    fi
    docker compose --project-name homecare --env-file "$directory/images.env" "${files[@]}" "$@"
}

activate() {
    local directory=$1
    compose "$directory" up -d --remove-orphans --wait --wait-timeout 180 || return 1
    compose "$directory" exec -T nginx nginx -t || return 1
    compose "$directory" exec -T nginx nginx -s reload || return 1
    curl -fsS --retry 5 --retry-delay 2 --retry-all-errors --max-time 5 \
        https://43.202.80.116/actuator/health |
        python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' || return 1
}

finish() {
    local status=$?
    trap - EXIT TERM INT
    set +e
    if (( changed && status != 0 )); then
        if [[ -n "$previous" ]]; then
            install -m 600 "$previous/runtime.snapshot.env" /opt/homecare/config/.runtime-rollback
            mv -f /opt/homecare/config/.runtime-rollback /opt/homecare/config/runtime.env
            if activate "$previous"; then
                ln -sfn "$previous" /opt/homecare/.current-next
                mv -Tf /opt/homecare/.current-next /opt/homecare/current
                printf 'ROLLBACK_OK: restored previous release %s.\n' "$(basename "$previous")" >&2
            else
                printf 'ROLLBACK_FAILED: manual recovery is required.\n' >&2
            fi
        else
            printf 'FIRST_DEPLOY_FAILED: no previous healthy release exists.\n' >&2
        fi
    fi
    rm -f "$release_dir/runtime.prepared.env"
    rm -rf "$configdir"
    exit "$status"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

if [[ "$previous" == "$release_dir" ]]; then
    activate "$release_dir"
    printf 'Current release is healthy; no settings were refreshed.\n'
    exit 0
fi

# Prepare everything before replacing the running containers or runtime.env.
python3 "$release_dir/write-runtime-env.py" --output "$release_dir/runtime.prepared.env"
compose "$release_dir" config --quiet
aws ecr get-login-password --region ap-northeast-2 |
    docker login --username AWS --password-stdin \
        528215464049.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null
compose "$release_dir" pull
backend_image=$(sed -n 's/^BACKEND_IMAGE=//p' "$release_dir/images.env")
python3 "$release_dir/migrate-database.py" --image "$backend_image"
if [[ -n "$previous" ]]; then
    test -r "$previous/compose.prod.yaml"
    test -r "$previous/images.env"
    install -m 600 /opt/homecare/config/runtime.env "$previous/runtime.snapshot.env"
fi

# From this point a failure restores both the previous images and runtime.env.
changed=1
install -m 600 "$release_dir/runtime.prepared.env" /opt/homecare/config/.runtime-next
mv -f /opt/homecare/config/.runtime-next /opt/homecare/config/runtime.env
activate "$release_dir"
ln -sfn "$release_dir" /opt/homecare/.current-next
mv -Tf /opt/homecare/.current-next /opt/homecare/current
if [[ -n "$previous" ]]; then
    ln -sfn "$previous" /opt/homecare/previous
fi
changed=0
printf 'DEPLOY_OK: %s is healthy.\n' "$(basename "$release_dir")"
