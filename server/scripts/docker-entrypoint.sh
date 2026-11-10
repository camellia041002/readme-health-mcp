#!/usr/bin/env bash
set -Eeuo pipefail

# Docker secrets are files, while the legacy ingest/read compatibility paths
# are environment variables in the backend. Read them without logging and
# replace the shell with uvicorn so no shell remains as PID 1.
secret_value() {
    local path=$1 value
    if [[ ! -r "$path" ]]; then
        printf 'HealthQuery secret is missing or unreadable: %s\n' "$path" >&2
        exit 78
    fi
    value=$(<"$path")
    # Secret files may have one final newline, but must not contain whitespace
    # or additional lines. This prevents accidental weak/ambiguous credentials.
    if [[ -z "$value" || "$value" =~ [[:space:]] ]]; then
        printf 'HealthQuery secret has invalid whitespace or is empty: %s\n' "$path" >&2
        exit 78
    fi
    printf '%s' "$value"
}

ingest_token=$(secret_value /run/secrets/ingest_token)
read_token=$(secret_value /run/secrets/read_token)
admin_hash=$(secret_value /run/secrets/admin_password_hash)
session_secret=$(secret_value /run/secrets/session_secret)

if (( ${#ingest_token} < 32 || ${#read_token} < 32 )); then
    printf 'HealthQuery ingest/read secrets must each be at least 32 characters.\n' >&2
    exit 78
fi
if [[ "$ingest_token" == "$read_token" ]]; then
    printf 'HealthQuery ingest/read secrets must be different.\n' >&2
    exit 78
fi
if [[ "$admin_hash" != \$argon2* ]]; then
    printf 'HealthQuery administrator secret must be an Argon2 hash.\n' >&2
    exit 78
fi
if (( ${#session_secret} < 43 )); then
    printf 'HealthQuery session secret must be at least 43 characters.\n' >&2
    exit 78
fi

export HEALTHQUERY_INGEST_TOKEN="$ingest_token"
export HEALTHQUERY_READ_TOKEN="$read_token"
unset ingest_token read_token admin_hash session_secret

exec "$@"
