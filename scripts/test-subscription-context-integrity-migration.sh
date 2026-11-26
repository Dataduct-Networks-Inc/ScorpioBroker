#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
migration="$repo_root/AllInOneRunner/src/main/resources/db/migration/V20260825.1__subscription_context_integrity.sql"
database_url="${SCORPIO_MIGRATION_TEST_DATABASE_URL:-postgresql://postgres:postgres@127.0.0.1:5432/scorpio}"
psql_cmd=(psql "$database_url" -X -v ON_ERROR_STOP=1)

"${psql_cmd[@]}" <<'SQL'
DROP TABLE IF EXISTS public.subscriptions;
DROP TABLE IF EXISTS public.contexts;

CREATE TABLE public.contexts (
    id text PRIMARY KEY,
    body jsonb NOT NULL,
    kind text NOT NULL
);

CREATE TABLE public.subscriptions (
    subscription_id text PRIMARY KEY,
    subscription jsonb,
    context text
);

INSERT INTO public.contexts (id, body, kind)
VALUES ('urn:context:valid', '{"@context": {}}', 'ImplicitlyCreated');

INSERT INTO public.subscriptions (subscription_id, subscription, context)
VALUES
    ('urn:subscription:valid', '{}', 'urn:context:valid'),
    ('urn:subscription:historical-orphan', '{}', 'urn:context:missing');
SQL

"${psql_cmd[@]}" -f "$migration"

not_valid_count="$("${psql_cmd[@]}" -Atc "SELECT count(*) FROM pg_constraint WHERE conname IN ('subscriptions_context_required_chk', 'subscriptions_context_fkey') AND NOT convalidated")"
test "$not_valid_count" = "2"

if "${psql_cmd[@]}" -c "INSERT INTO public.subscriptions (subscription_id, subscription, context) VALUES ('urn:subscription:null', '{}', NULL)"; then
    echo "expected the context check constraint to reject a new NULL context" >&2
    exit 1
fi

if "${psql_cmd[@]}" -c "INSERT INTO public.subscriptions (subscription_id, subscription, context) VALUES ('urn:subscription:new-orphan', '{}', 'urn:context:absent')"; then
    echo "expected the context foreign key to reject a new orphan" >&2
    exit 1
fi

if "${psql_cmd[@]}" -c "DELETE FROM public.contexts WHERE id = 'urn:context:valid'"; then
    echo "expected ON DELETE RESTRICT to protect a referenced context" >&2
    exit 1
fi

"${psql_cmd[@]}" <<'SQL'
INSERT INTO public.contexts (id, body, kind)
VALUES ('urn:context:missing', '{"@context": {}}', 'ImplicitlyCreated');

ALTER TABLE public.subscriptions VALIDATE CONSTRAINT subscriptions_context_required_chk;
ALTER TABLE public.subscriptions VALIDATE CONSTRAINT subscriptions_context_fkey;
SQL

validated_count="$("${psql_cmd[@]}" -Atc "SELECT count(*) FROM pg_constraint WHERE conname IN ('subscriptions_context_required_chk', 'subscriptions_context_fkey') AND convalidated")"
test "$validated_count" = "2"
