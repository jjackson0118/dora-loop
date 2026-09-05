-- Raw events, stored as received. The metrics are computed on read by
-- DoraCalculator rather than maintained incrementally, because a stored
-- aggregate drifts from its inputs silently and this project is about not
-- doing that.

CREATE TABLE deployment_event (
    id            TEXT        PRIMARY KEY,
    service       TEXT        NOT NULL,
    environment   TEXT        NOT NULL,
    deployed_at   TIMESTAMPTZ NOT NULL,
    outcome       TEXT        NOT NULL,
    payload_hash  TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per commit carried by a deployment. Lead time is defined per change,
-- so the range has to be stored, not just the head commit. See ADR 0002.
CREATE TABLE deployment_change (
    deployment_id TEXT        NOT NULL REFERENCES deployment_event(id) ON DELETE CASCADE,
    ordinal       INT         NOT NULL,
    commit_sha    TEXT        NOT NULL,
    authored_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (deployment_id, ordinal)
);

CREATE TABLE incident_event (
    id                   TEXT        PRIMARY KEY,
    service              TEXT        NOT NULL,
    caused_by_commit_sha TEXT,
    detected_at          TIMESTAMPTZ NOT NULL,
    resolved_at          TIMESTAMPTZ,
    payload_hash         TEXT        NOT NULL,
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deployment_service_time ON deployment_event (service, deployed_at);
CREATE INDEX idx_incident_service_time   ON incident_event   (service, detected_at);
CREATE INDEX idx_change_sha              ON deployment_change (commit_sha);
