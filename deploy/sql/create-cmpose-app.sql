-- Creates cmpose-app's own role and database on the shared PostgreSQL. Run once, as a superuser, through psql 15 or
-- newer; the password comes from the CMPOSE_APP_DB_PASSWORD environment variable, never from this file or from the
-- command line, where `ps` would show it (see deploy/README.md):
--   docker exec -i -e CMPOSE_APP_DB_PASSWORD postgres psql -U postgres -f - < create-cmpose-app.sql
-- Flyway creates the tables at the app's first start, owned by this role.
\set ON_ERROR_STOP on

\getenv password CMPOSE_APP_DB_PASSWORD
\if :{?password}
\else
\set password ''
\endif
SELECT :'password' = '' AS password_missing \gset
\if :password_missing
DO $$ BEGIN RAISE EXCEPTION 'CMPOSE_APP_DB_PASSWORD is not set: nothing was created'; END $$;
\endif

CREATE ROLE cmpose_app LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE cmpose_app OWNER cmpose_app;
-- Only the owner may connect; other apps' roles get nothing here, and this role nothing elsewhere.
REVOKE ALL ON DATABASE cmpose_app FROM PUBLIC;

\connect cmpose_app
-- Before PostgreSQL 15 every role could create objects in `public`; hand the schema to the app role alone.
ALTER SCHEMA public OWNER TO cmpose_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
