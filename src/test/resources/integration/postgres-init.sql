CREATE ROLE inventory_owner NOLOGIN;
GRANT inventory_owner TO postgres;

CREATE ROLE debezium LOGIN REPLICATION PASSWORD 'debezium';
GRANT inventory_owner TO debezium;

ALTER SCHEMA public OWNER TO inventory_owner;
GRANT CONNECT, CREATE ON DATABASE inventory_db TO inventory_owner;

