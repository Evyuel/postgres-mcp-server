-- Запускайте psql от имени администратора:
-- psql -v metadata_password='use-a-secret-manager-value' -f docs/create-metadata-role.sql
CREATE ROLE postgres_metadata
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'metadata_password';

-- Роль видит объекты схемы через системные каталоги, но не получает SELECT
-- на бизнес-таблицы. Повторите GRANT только для разрешённых схем.
GRANT CONNECT ON DATABASE postgres TO postgres_metadata;
GRANT USAGE ON SCHEMA app TO postgres_metadata;

ALTER ROLE postgres_metadata SET default_transaction_read_only = on;
ALTER ROLE postgres_metadata SET statement_timeout = '10s';
ALTER ROLE postgres_metadata SET lock_timeout = '2s';

