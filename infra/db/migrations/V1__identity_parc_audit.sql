-- NexFleet CAMTEL — V1 : identité, organisation, parc, conducteurs, audit
-- Exigences : NF-01/02 (comptes, rôles), NF-10 (véhicules), NF-12 (conducteurs),
-- NF-17 (kilométrage), RG-01 (historique), RG-03 (concurrence), RG-04 (archivage),
-- RG-05 (UTC), NF-53 (journal), NF-04 (séparation démo).
-- Les extensions sont créées par le DBA (droits superutilisateur) ; ces ordres sont sans effet si elles existent.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------- environnement (NF-04)
CREATE TABLE app_environment (
    singleton   boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    kind        text NOT NULL CHECK (kind IN ('DEV','TEST','DEMO','PREPROD','PRODUCTION')),
    declared_at timestamptz NOT NULL DEFAULT now()
);

-- Toute insertion de données marquées démo est refusée hors DEV/TEST/DEMO.
CREATE FUNCTION nf_assert_demo_allowed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.is_demo AND COALESCE((SELECT kind FROM app_environment), 'PRODUCTION')
                       NOT IN ('DEV','TEST','DEMO') THEN
        RAISE EXCEPTION 'NF-04 : données de démonstration interdites dans cet environnement'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- ---------------------------------------------------------------- organisation et identité
CREATE TABLE organisation_unit (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    parent_id   uuid REFERENCES organisation_unit(id),
    is_demo     boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_org_demo BEFORE INSERT OR UPDATE ON organisation_unit
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

CREATE TABLE app_user (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login           text NOT NULL,
    login_normalized text GENERATED ALWAYS AS (lower(btrim(login))) STORED,
    display_name    text NOT NULL,
    email           text,
    hr_identifier   text UNIQUE,                    -- NF-50 : identifiant RH stable
    password_hash   text,                           -- bcrypt/argon2 ; NULL si fédéré
    org_unit_id     uuid NOT NULL REFERENCES organisation_unit(id),
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','DISABLED')),
    failed_logins   integer NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until    timestamptz,
    is_demo         boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_user_login ON app_user(login_normalized);
CREATE TRIGGER trg_user_demo BEFORE INSERT OR UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

CREATE TABLE role (
    code        text PRIMARY KEY,            -- FLEET_MANAGER, DRIVER, MAINTENANCE, CONTROLLER, ADMIN, AUDITOR
    label       text NOT NULL
);

CREATE TABLE permission (
    code        text PRIMARY KEY,            -- ex. vehicle:read, vehicle:write, fuel_order:approve
    label       text NOT NULL
);

CREATE TABLE role_permission (
    role_code       text NOT NULL REFERENCES role(code),
    permission_code text NOT NULL REFERENCES permission(code),
    PRIMARY KEY (role_code, permission_code)
);

-- Attribution d'un rôle sur un périmètre (entité + sous-entités), avec fin obligatoire pour les délégations.
CREATE TABLE user_role_grant (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user(id),
    role_code     text NOT NULL REFERENCES role(code),
    scope_org_id  uuid NOT NULL REFERENCES organisation_unit(id),
    is_delegation boolean NOT NULL DEFAULT false,
    valid_from    timestamptz NOT NULL DEFAULT now(),
    valid_until   timestamptz,
    granted_by    uuid REFERENCES app_user(id),
    revoked_at    timestamptz,
    CONSTRAINT ck_delegation_has_end CHECK (NOT is_delegation OR valid_until IS NOT NULL),
    CONSTRAINT ck_grant_period CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_no_self_grant CHECK (granted_by IS NULL OR granted_by <> user_id)   -- NF-02
);
CREATE INDEX ix_grant_user ON user_role_grant(user_id) WHERE revoked_at IS NULL;

-- Sessions : seul le condensé du jeton est stocké (SEC-01, NF-01 révocation).
CREATE TABLE auth_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user(id),
    token_sha256  bytea NOT NULL UNIQUE,
    client_kind   text NOT NULL CHECK (client_kind IN ('DESKTOP','ANDROID','API')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    revoke_reason text,
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_session_user_active ON auth_session(user_id) WHERE revoked_at IS NULL;

-- Une suspension/désactivation révoque immédiatement toutes les sessions (NF-50, REC-08).
CREATE FUNCTION nf_revoke_sessions_on_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'ACTIVE' AND OLD.status = 'ACTIVE' THEN
        UPDATE auth_session SET revoked_at = now(), revoke_reason = 'USER_' || NEW.status
         WHERE user_id = NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_user_status_revoke AFTER UPDATE OF status ON app_user
    FOR EACH ROW EXECUTE FUNCTION nf_revoke_sessions_on_status();

-- ---------------------------------------------------------------- journal d'audit (NF-53, RG-01)
-- Ajout seul : ni UPDATE ni DELETE ; chaînage par empreinte pour rendre l'altération détectable (SEC-08).
CREATE TABLE audit_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_id      uuid REFERENCES app_user(id),
    action        text NOT NULL,
    object_type   text NOT NULL,
    object_id     text NOT NULL,
    reason        text,
    old_value     jsonb,
    new_value     jsonb,
    request_id    text,
    prev_hash     bytea,
    row_hash      bytea NOT NULL
);
CREATE INDEX ix_audit_object ON audit_event(object_type, object_id);

CREATE FUNCTION nf_audit_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev bytea;
BEGIN
    PERFORM pg_advisory_xact_lock(424242);                       -- sérialise le chaînage
    SELECT row_hash INTO prev FROM audit_event ORDER BY id DESC LIMIT 1;
    NEW.prev_hash := prev;
    NEW.row_hash := digest(
        coalesce(encode(prev,'hex'),'') || '|' || NEW.occurred_at::text || '|' ||
        coalesce(NEW.actor_id::text,'') || '|' || NEW.action || '|' || NEW.object_type || '|' ||
        NEW.object_id || '|' || coalesce(NEW.reason,'') || '|' ||
        coalesce(NEW.old_value::text,'') || '|' || coalesce(NEW.new_value::text,''), 'sha256');
    RETURN NEW;
END $$;
CREATE TRIGGER trg_audit_chain BEFORE INSERT ON audit_event
    FOR EACH ROW EXECUTE FUNCTION nf_audit_chain();

CREATE FUNCTION nf_forbid_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Table % en ajout seul', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- ---------------------------------------------------------------- parc (NF-10, NF-11)
CREATE TABLE vehicle (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    registration            text NOT NULL,
    -- Normalisation : majuscules, sans espaces ni tirets (« ce-4401 lt » = « CE4401LT »).
    registration_normalized text GENERATED ALWAYS AS (upper(regexp_replace(registration, '[^A-Za-z0-9]', '', 'g'))) STORED,
    vin                     text,
    vin_normalized          text GENERATED ALWAYS AS (upper(regexp_replace(vin, '[^A-Za-z0-9]', '', 'g'))) STORED,
    make                    text NOT NULL,
    model                   text NOT NULL,
    model_year              smallint CHECK (model_year BETWEEN 1950 AND 2100),
    vehicle_type            text,
    energy                  text CHECK (energy IN ('DIESEL','ESSENCE','HYBRIDE','ELECTRIQUE','GPL','AUTRE')),
    engine_number           text,
    power_kw                numeric(7,2) CHECK (power_kw > 0),
    tank_capacity_l         numeric(7,2) CHECK (tank_capacity_l > 0),          -- CAR-04 (capacité vérifiée)
    tank_capacity_verified  boolean NOT NULL DEFAULT false,
    status                  text NOT NULL DEFAULT 'AVAILABLE'
                            CHECK (status IN ('AVAILABLE','IN_SERVICE','ON_MISSION','MAINTENANCE','OUT_OF_SERVICE','ARCHIVED')),
    org_unit_id             uuid NOT NULL REFERENCES organisation_unit(id),
    catalog_variant_id      uuid,                                              -- lien catalogue (V3)
    model_asset_status      text NOT NULL DEFAULT 'NONE' CHECK (model_asset_status IN ('EXACT','APPROXIMATE','GENERIC','NONE')),
    is_demo                 boolean NOT NULL DEFAULT false,
    archived_at             timestamptz,
    version                 bigint NOT NULL DEFAULT 0,                         -- RG-03 verrou optimiste
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_registration_not_blank CHECK (length(upper(regexp_replace(registration, '[^A-Za-z0-9]', '', 'g'))) >= 4),
    CONSTRAINT ck_archived_status CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);
-- Unicité vérifiée même après archivage : un doublon est refusé (NF-10 réception).
CREATE UNIQUE INDEX ux_vehicle_registration ON vehicle(registration_normalized);
CREATE UNIQUE INDEX ux_vehicle_vin ON vehicle(vin_normalized) WHERE vin_normalized IS NOT NULL AND vin_normalized <> '';
CREATE INDEX ix_vehicle_org_status ON vehicle(org_unit_id, status);
CREATE TRIGGER trg_vehicle_demo BEFORE INSERT OR UPDATE ON vehicle
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();
-- Suppression physique interdite : archivage métier (RG-04).
CREATE TRIGGER trg_vehicle_no_delete BEFORE DELETE ON vehicle
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE TABLE vehicle_document (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id    uuid NOT NULL REFERENCES vehicle(id),
    doc_type      text NOT NULL CHECK (doc_type IN ('ASSURANCE','CONTROLE_TECHNIQUE','VIGNETTE','CARTE_GRISE','AUTRE')),
    reference     text,
    valid_from    date,
    valid_until   date NOT NULL,                       -- RG-05 : échéance = date civile
    file_id       uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_from IS NULL OR valid_until >= valid_from)
);
CREATE INDEX ix_vehicle_doc_due ON vehicle_document(valid_until);

-- ---------------------------------------------------------------- conducteurs (NF-12)
CREATE TABLE driver (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid UNIQUE REFERENCES app_user(id),
    staff_number  text NOT NULL UNIQUE,
    full_name     text NOT NULL,
    phone         text,
    org_unit_id   uuid NOT NULL REFERENCES organisation_unit(id),
    is_demo       boolean NOT NULL DEFAULT false,
    archived_at   timestamptz,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_driver_demo BEFORE INSERT OR UPDATE ON driver
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

CREATE TABLE driver_license (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id     uuid NOT NULL REFERENCES driver(id),
    license_number text NOT NULL,
    categories    text[] NOT NULL,
    issued_on     date,
    expires_on    date NOT NULL,
    file_id       uuid,
    UNIQUE (driver_id, license_number)
);

-- Affectations : une nouvelle affectation clôt la précédente ; aucun chevauchement pour un même véhicule.
CREATE TABLE vehicle_assignment (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id    uuid NOT NULL REFERENCES vehicle(id),
    driver_id     uuid REFERENCES driver(id),
    org_unit_id   uuid NOT NULL REFERENCES organisation_unit(id),
    period        tstzrange NOT NULL,
    created_by    uuid REFERENCES app_user(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT isempty(period)),
    CONSTRAINT ex_vehicle_assignment_overlap EXCLUDE USING gist (vehicle_id WITH =, period WITH &&),
    CONSTRAINT ex_driver_assignment_overlap  EXCLUDE USING gist (driver_id WITH =, period WITH &&)
);

-- ---------------------------------------------------------------- kilométrage (NF-17)
CREATE TABLE vehicle_odometer (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id       uuid NOT NULL REFERENCES vehicle(id),
    read_at          timestamptz NOT NULL,
    value_km         numeric(10,1) NOT NULL CHECK (value_km >= 0),
    source           text NOT NULL CHECK (source IN ('SAISIE','PLEIN','MAINTENANCE','GPS_ESTIME','IMPORT')),
    counter_epoch    integer NOT NULL DEFAULT 0,   -- incrémenté lors d'un changement de compteur documenté
    author_id        uuid REFERENCES app_user(id),
    proof_file_id    uuid,
    justification    text,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_odometer_vehicle_time ON vehicle_odometer(vehicle_id, counter_epoch, read_at);

-- ---------------------------------------------------------------- référentiel des rôles et permissions
INSERT INTO role(code, label) VALUES
 ('FLEET_MANAGER','Gestionnaire flotte'), ('DRIVER','Agent ou conducteur'),
 ('MAINTENANCE','Maintenance'), ('CONTROLLER','Contrôleur'),
 ('ADMIN','Administrateur technique'), ('AUDITOR','Auditeur');

INSERT INTO permission(code, label) VALUES
 ('vehicle:read','Consulter le parc'), ('vehicle:write','Créer et corriger un véhicule'),
 ('vehicle:archive','Archiver un véhicule'), ('driver:read','Consulter les conducteurs'),
 ('driver:write','Gérer les conducteurs'), ('fuel_order:create','Demander un bon carburant'),
 ('fuel_order:approve','Valider un bon carburant'), ('fuel_fill:create','Saisir un plein'),
 ('anomaly:read','Consulter les anomalies'), ('anomaly:review','Instruire une anomalie'),
 ('mission:manage','Créer et autoriser une mission'), ('mission:drive','Exécuter une mission affectée'),
 ('gps:read','Consulter les trajets'), ('chat:use','Utiliser la messagerie'),
 ('audit:read','Consulter et exporter le journal'), ('admin:users','Administrer les comptes'),
 ('admin:settings','Administrer les paramètres');

INSERT INTO role_permission(role_code, permission_code) VALUES
 ('FLEET_MANAGER','vehicle:read'),('FLEET_MANAGER','vehicle:write'),('FLEET_MANAGER','vehicle:archive'),
 ('FLEET_MANAGER','driver:read'),('FLEET_MANAGER','driver:write'),('FLEET_MANAGER','fuel_order:create'),
 ('FLEET_MANAGER','fuel_order:approve'),('FLEET_MANAGER','mission:manage'),('FLEET_MANAGER','gps:read'),
 ('FLEET_MANAGER','chat:use'),('FLEET_MANAGER','anomaly:read'),
 ('DRIVER','vehicle:read'),('DRIVER','fuel_order:create'),('DRIVER','fuel_fill:create'),
 ('DRIVER','mission:drive'),('DRIVER','chat:use'),
 ('MAINTENANCE','vehicle:read'),('MAINTENANCE','chat:use'),
 ('CONTROLLER','vehicle:read'),('CONTROLLER','driver:read'),('CONTROLLER','anomaly:read'),
 ('CONTROLLER','anomaly:review'),('CONTROLLER','fuel_order:approve'),('CONTROLLER','gps:read'),('CONTROLLER','chat:use'),
 ('ADMIN','admin:users'),('ADMIN','admin:settings'),
 ('AUDITOR','vehicle:read'),('AUDITOR','driver:read'),('AUDITOR','anomaly:read'),('AUDITOR','audit:read');
-- ADMIN n'a aucun pouvoir métier implicite (matrice des habilitations, chap. 03).
