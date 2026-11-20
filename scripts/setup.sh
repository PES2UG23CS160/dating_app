#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo '.env already exists; keeping your secrets.'
else
  umask 077
  printf 'DB_PASSWORD=%s\nJWT_SECRET=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 48)" > .env
  echo 'Created local secrets in .env.'
fi
