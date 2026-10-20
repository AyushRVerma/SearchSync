#!/bin/sh
set -eu

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_user="$POSTGRES_APP_USER" \
  --set=app_password="$POSTGRES_APP_PASSWORD" \
  --set=debezium_user="$DEBEZIUM_USER" \
  --set=debezium_password="$DEBEZIUM_PASSWORD" <<'SQL'
CREATE ROLE inventory_owner NOLOGIN;
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('CREATE ROLE %I LOGIN REPLICATION PASSWORD %L', :'debezium_user', :'debezium_password') \gexec
SELECT format('GRANT inventory_owner TO %I', :'app_user') \gexec
SELECT format('GRANT inventory_owner TO %I', :'debezium_user') \gexec
ALTER SCHEMA public OWNER TO inventory_owner;
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO inventory_owner', current_database()) \gexec
SQL

