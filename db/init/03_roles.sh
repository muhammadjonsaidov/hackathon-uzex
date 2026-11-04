#!/bin/sh
# The app connects only as this read-only role, so SQL written by the LLM cannot change data
# even if every check in the application were bypassed.
set -eu

psql -v ON_ERROR_STOP=1 \
     -v agent_user="$AGENT_DB_USER" \
     -v agent_password="$AGENT_DB_PASSWORD" \
     -v db_name="$POSTGRES_DB" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE :"agent_user" LOGIN PASSWORD :'agent_password' CONNECTION LIMIT 30;
ALTER ROLE :"agent_user" SET default_transaction_read_only = on;
ALTER ROLE :"agent_user" SET statement_timeout = '20s';
ALTER ROLE :"agent_user" SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE :"agent_user" SET search_path = public;
REVOKE ALL ON SCHEMA raw FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE :"db_name" FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"agent_user";
GRANT SELECT ON deals, broker_deals, contracts, participants, brokers, usd_rates, regions, product_catalog TO :"agent_user";
SQL
