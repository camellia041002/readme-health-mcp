#!/usr/bin/env bash
set -Eeuo pipefail

# Generate the four files consumed by docker-compose.example.yml. The admin
# password is read from the controlling terminal with echo disabled and is
# piped directly to Argon2; it is never written to disk or printed.
umask 077

secrets_dir=${1:-./secrets}
force=0
if [[ "${1:-}" == "--force" ]]; then
    secrets_dir=./secrets
    force=1
elif [[ "${2:-}" == "--force" ]]; then
    force=1
fi
if [[ "$secrets_dir" == "--force" ]]; then
    secrets_dir=./secrets
fi

mkdir -p -- "$secrets_dir"
chmod 700 -- "$secrets_dir"

if (( ! force )); then
    for path in \
        "$secrets_dir/admin-password-hash" \
        "$secrets_dir/session-secret" \
        "$secrets_dir/ingest-token" \
        "$secrets_dir/read-token"; do
        if [[ -e "$path" ]]; then
            printf 'Refusing to overwrite existing secret files (use --force): %s\n' "$path" >&2
            exit 1
        fi
    done
fi

write_secret() {
    local path=$1 value=$2 tmp
    tmp=$(mktemp "$secrets_dir/.healthquery-secret.XXXXXX")
    printf '%s\n' "$value" >"$tmp"
    # Compose bind-mounts file-backed secrets without implementing uid/gid/mode
    # remapping. The 0700 parent directory protects them on the host; 0444 lets
    # the non-root UID 1001 read the individual mounts inside the container.
    chmod 444 -- "$tmp"
    mv -f -- "$tmp" "$path"
}

random_secret() {
    python3 -c 'import secrets; print(secrets.token_urlsafe(48), end="")'
}

hash_admin_password() {
    local hash
    if command -v uv >/dev/null 2>&1; then
        # --no-project keeps this helper independent of the checkout's Python
        # environment while uv installs only into its ephemeral cache.
        hash=$(uv run --quiet --no-project --with 'pwdlib[argon2]' python -c \
            'from pwdlib import PasswordHash; import sys; print(PasswordHash.recommended().hash(sys.stdin.read()), end="")')
    elif python3 -c 'import pwdlib' >/dev/null 2>&1; then
        hash=$(python3 -c \
            'from pwdlib import PasswordHash; import sys; print(PasswordHash.recommended().hash(sys.stdin.read()), end="")')
    else
        printf 'Install uv (recommended) or pwdlib[argon2] before generating the admin hash.\n' >&2
        exit 1
    fi
    if [[ "$hash" != \$argon2* ]]; then
        printf 'Argon2 hashing did not return an Argon2 hash.\n' >&2
        exit 1
    fi
    printf '%s' "$hash"
}

if [[ ! -r /dev/tty || ! -w /dev/tty ]]; then
    printf 'An interactive terminal is required so the admin password is never echoed or logged.\n' >&2
    exit 1
fi

cleanup() {
    unset ADMIN_PASSWORD ADMIN_PASSWORD_CONFIRM ADMIN_HASH
}
trap cleanup EXIT

IFS= read -r -s -p 'Admin password (hidden): ' ADMIN_PASSWORD </dev/tty
printf '\n' >/dev/tty
IFS= read -r -s -p 'Confirm admin password (hidden): ' ADMIN_PASSWORD_CONFIRM </dev/tty
printf '\n' >/dev/tty
if [[ ${#ADMIN_PASSWORD} -lt 16 ]]; then
    printf 'Admin password must contain at least 16 characters. No secret files were written.\n' >&2
    exit 1
fi
if [[ "$ADMIN_PASSWORD" != "$ADMIN_PASSWORD_CONFIRM" ]]; then
    printf 'Admin passwords were empty or did not match. No secret files were written.\n' >&2
    exit 1
fi

ADMIN_HASH=$(printf '%s' "$ADMIN_PASSWORD" | hash_admin_password)
write_secret "$secrets_dir/admin-password-hash" "$ADMIN_HASH"
write_secret "$secrets_dir/session-secret" "$(random_secret)"
write_secret "$secrets_dir/ingest-token" "$(random_secret)"
write_secret "$secrets_dir/read-token" "$(random_secret)"

printf 'Generated Argon2 admin hash, session secret, and separate ingest/read tokens in %s.\n' "$secrets_dir"
printf 'The admin password itself was not stored or displayed. Keep this directory private.\n'
