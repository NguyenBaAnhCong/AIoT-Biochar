#!/usr/bin/env bash
set -e

if [ "${SEED_DEMO:-false}" = "true" ]; then
    psql -v ON_ERROR_STOP=1 \
        --username "${POSTGRES_USER}" \
        --dbname "${POSTGRES_DB}" \
        --file /seed/99_demo_seed.sql
fi
