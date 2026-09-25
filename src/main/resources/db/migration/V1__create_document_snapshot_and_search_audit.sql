-- Last known documents per VIN and source system: the fallback served (flagged stale)
-- when a source system is unavailable.
create table document_snapshot (
    id            uuid          primary key,
    vin           varchar(17)   not null,
    source        varchar(16)   not null,
    external_id   varchar(128)  not null,
    document_type varchar(64),
    title         varchar(512),
    reference     varchar(128),
    issued_at     timestamp with time zone,
    url           varchar(2048),
    fetched_at    timestamp with time zone not null,
    constraint uk_document_snapshot_vin_source_external unique (vin, source, external_id)
);

-- Every read and every refresh is by (vin, source); the unique constraint's index leads with
-- the same columns, so it serves those lookups without a separate index.

-- One row per search: which VIN was looked up, when, and how each source behaved.
create table search_audit (
    id              uuid          primary key,
    vin             varchar(17)   not null,
    correlation_id  varchar(64),
    requested_at    timestamp with time zone not null,
    duration_ms     bigint        not null,
    partial         boolean       not null,
    document_count  integer       not null,
    source_outcomes varchar(256)  not null
);

create index ix_search_audit_vin_requested_at on search_audit (vin, requested_at desc);
