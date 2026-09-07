CREATE SCHEMA IF NOT EXISTS app;

CREATE TABLE app.customer (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email varchar(320) NOT NULL,
    balance numeric(12,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    tags text[] DEFAULT '{}',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_email UNIQUE (email)
);
COMMENT ON TABLE app.customer IS 'Customers';
COMMENT ON COLUMN app.customer.email IS 'Login email';

CREATE TABLE app.purchase (
    customer_id bigint NOT NULL,
    order_no bigint NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('NEW', 'PAID')),
    total numeric(10,2),
    PRIMARY KEY (customer_id, order_no),
    CONSTRAINT fk_purchase_customer FOREIGN KEY (customer_id)
        REFERENCES app.customer(id) ON UPDATE CASCADE ON DELETE RESTRICT
);
CREATE INDEX ix_purchase_paid ON app.purchase (customer_id) WHERE status = 'PAID';
CREATE INDEX ix_purchase_status_lower ON app.purchase ((lower(status)));
CREATE INDEX ix_customer_tags_gin ON app.customer USING gin (tags);

CREATE VIEW app.customer_view AS SELECT id, email FROM app.customer;

CREATE TABLE app.events (
    id bigint NOT NULL,
    occurred_on date NOT NULL,
    payload jsonb,
    PRIMARY KEY (id, occurred_on)
) PARTITION BY RANGE (occurred_on);
CREATE TABLE app.events_2026 PARTITION OF app.events
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE FUNCTION app.lookup_customer(p_id bigint) RETURNS text
LANGUAGE sql STABLE AS $$ SELECT email FROM app.customer WHERE id = p_id $$;
CREATE FUNCTION app.lookup_customer(p_email text) RETURNS bigint
LANGUAGE sql STABLE AS $$ SELECT id FROM app.customer WHERE email = p_email $$;
CREATE PROCEDURE app.clear_old_events(p_before date)
LANGUAGE sql AS $$ DELETE FROM app.events WHERE occurred_on < p_before $$;
