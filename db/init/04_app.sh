#!/bin/sh
# Users and chats live in their own schema under their own role. The agent's read-only role has
# no access here, so SQL written by the LLM can never read another person's chats.
set -eu

psql -v ON_ERROR_STOP=1 \
     -v app_user="$APP_DB_USER" \
     -v app_password="$APP_DB_PASSWORD" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE SCHEMA app;
REVOKE ALL ON SCHEMA app FROM PUBLIC;

CREATE TABLE app.users (
  id uuid PRIMARY KEY,
  -- Both empty for a guest. Only a BCrypt hash of the password is stored.
  username text,
  password_hash text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((username IS NULL) = (password_hash IS NULL))
);
CREATE UNIQUE INDEX users_username ON app.users (lower(username));

-- Only the hash of a browser's session token is stored, so a leaked table cannot log anyone in.
CREATE TABLE app.web_sessions (
  token_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE INDEX web_sessions_user ON app.web_sessions (user_id);

CREATE TABLE app.chats (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE CASCADE,
  lang text NOT NULL CHECK (lang IN ('ru', 'uz')),
  title text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chats_user_recent ON app.chats (user_id, updated_at DESC);

CREATE TABLE app.turns (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chat_id uuid NOT NULL REFERENCES app.chats (id) ON DELETE CASCADE,
  question text NOT NULL,
  response jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX turns_chat ON app.turns (chat_id, id);

CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' CONNECTION LIMIT 20;
ALTER ROLE :"app_user" SET search_path = app;
ALTER ROLE :"app_user" SET statement_timeout = '10s';
GRANT USAGE ON SCHEMA app TO :"app_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app TO :"app_user";
SQL
