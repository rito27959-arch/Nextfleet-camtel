-- NexFleet CAMTEL — V3 : missions et GPS (NF-70..74), anomalies (NF-43/44, NF-80..84),
-- chat (NF-60..64), catalogue et actifs 3D sous licence (extension V5), outbox.

-- ================================================================ MISSIONS ET GPS
CREATE TABLE mission (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reference       text NOT NULL UNIQUE,
    org_unit_id     uuid NOT NULL REFERENCES organisation_unit(id),
    vehicle_id      uuid NOT NULL REFERENCES vehicle(id),
    driver_id       uuid NOT NULL REFERENCES driver(id),
    purpose         text NOT NULL,
    planned_start   timestamptz NOT NULL,
    planned_end     timestamptz NOT NULL,
    status          text NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','AUTHORIZED','ACTIVE','FINISHED','CANCELLED')),
    authorized_by   uuid REFERENCES app_user(id),
    authorized_at   timestamptz,
    started_at      timestamptz,
    finished_at     timestamptz,
    created_by      uuid NOT NULL REFERENCES app_user(id),
    is_demo         boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (planned_end > planned_start),
    CHECK (status NOT IN ('AUTHORIZED','ACTIVE','FINISHED') OR authorized_by IS NOT NULL),
    CHECK (status NOT IN ('ACTIVE','FINISHED') OR started_at IS NOT NULL),
    CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL) OR status = 'CANCELLED'),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE TRIGGER trg_mission_demo BEFORE INSERT OR UPDATE ON mission
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

-- Consentement / information et autorisation de collecte pour un appareil donné (NF-70).
CREATE TABLE tracking_authorization (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id        uuid NOT NULL REFERENCES mission(id),
    source_device_id  text NOT NULL,
    informed_at       timestamptz NOT NULL,          -- information explicite du conducteur
    notice_version    text NOT NULL,                 -- version de la notice affichée
    granted_at        timestamptz NOT NULL DEFAULT now(),
    revoked_at        timestamptz,
    UNIQUE (mission_id, source_device_id)
);

CREATE TABLE location_point (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mission_id        uuid NOT NULL REFERENCES mission(id),
    vehicle_id        uuid NOT NULL REFERENCES vehicle(id),
    source_device_id  text NOT NULL,
    client_fix_id     text NOT NULL,
    recorded_at       timestamptz NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    position          geography(Point, 4326) NOT NULL,
    accuracy_m        real NOT NULL CHECK (accuracy_m >= 0),
    speed_mps         real CHECK (speed_mps >= 0),
    bearing_deg       real CHECK (bearing_deg >= 0 AND bearing_deg < 360),
    provider          text NOT NULL,
    quality_state     text NOT NULL DEFAULT 'UNQUALIFIED'
                      CHECK (quality_state IN ('UNQUALIFIED','VALID','IMPRECISE','JUMP','STALE')),
    UNIQUE (source_device_id, client_fix_id)          -- NF-74 : un lot rejoué ne double pas les points
);
CREATE INDEX ix_location_mission_time ON location_point(mission_id, recorded_at);

-- REC-GPS-01 (défense en profondeur côté base) : un point n'est accepté que s'il a été relevé
-- pendant la période ACTIVE d'une mission autorisée, par un appareil autorisé non révoqué.
-- Les points tamponnés hors réseau, relevés avant la clôture, restent acceptés après reconnexion.
CREATE FUNCTION nf_guard_location_point() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m mission%ROWTYPE; a tracking_authorization%ROWTYPE;
BEGIN
    SELECT * INTO m FROM mission WHERE id = NEW.mission_id;
    IF m.status NOT IN ('ACTIVE','FINISHED') OR m.started_at IS NULL THEN
        RAISE EXCEPTION 'GPS : mission % non démarrée — collecte interdite', m.reference
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.vehicle_id <> m.vehicle_id THEN
        RAISE EXCEPTION 'GPS : véhicule différent de celui de la mission' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.recorded_at < m.started_at OR (m.finished_at IS NOT NULL AND NEW.recorded_at > m.finished_at) THEN
        RAISE EXCEPTION 'GPS : point relevé hors période de mission (%)', NEW.recorded_at
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.recorded_at > now() + interval '2 minutes' THEN
        RAISE EXCEPTION 'GPS : horodatage dans le futur' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO a FROM tracking_authorization
     WHERE mission_id = NEW.mission_id AND source_device_id = NEW.source_device_id;
    IF NOT FOUND OR NEW.recorded_at < a.granted_at
       OR (a.revoked_at IS NOT NULL AND NEW.recorded_at > a.revoked_at) THEN
        RAISE EXCEPTION 'GPS : appareil % non autorisé pour cette mission à cet instant', NEW.source_device_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_location_guard BEFORE INSERT ON location_point
    FOR EACH ROW EXECUTE FUNCTION nf_guard_location_point();

-- La clôture d'une mission révoque les autorisations de collecte.
CREATE FUNCTION nf_mission_close_revokes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('FINISHED','CANCELLED') AND OLD.status NOT IN ('FINISHED','CANCELLED') THEN
        UPDATE tracking_authorization
           SET revoked_at = coalesce(NEW.finished_at, now())
         WHERE mission_id = NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_mission_close AFTER UPDATE OF status ON mission
    FOR EACH ROW EXECUTE FUNCTION nf_mission_close_revokes();

CREATE TABLE geofence (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_unit_id uuid NOT NULL REFERENCES organisation_unit(id),
    name        text NOT NULL,
    area        geography(Polygon, 4326) NOT NULL,
    approved_by uuid REFERENCES app_user(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ================================================================ ANOMALIES (NF-43, NF-44, NF-80, NF-84)
CREATE TABLE anomaly_rule (
    rule_id      text NOT NULL,                 -- ex. CAR-01
    rule_version integer NOT NULL CHECK (rule_version > 0),
    label        text NOT NULL,
    parameters   jsonb NOT NULL DEFAULT '{}',
    approved_by  uuid REFERENCES app_user(id),
    active_from  timestamptz NOT NULL DEFAULT now(),
    active_until timestamptz,
    PRIMARY KEY (rule_id, rule_version)
);

CREATE TABLE anomaly (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id       text NOT NULL,
    rule_version  integer NOT NULL,
    org_unit_id   uuid NOT NULL REFERENCES organisation_unit(id),
    vehicle_id    uuid REFERENCES vehicle(id),
    severity      text NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH')),
    data_quality  text NOT NULL CHECK (data_quality IN ('FIABLE','PARTIELLE','INSUFFISANTE')),
    summary       text NOT NULL,
    evidence      jsonb NOT NULL,               -- entrées, unités, seuil, période, identifiants des pièces
    period        tstzrange,
    -- Aucun statut « fraude » : la qualification reste humaine (NF-44, NF-83).
    status        text NOT NULL DEFAULT 'A_VERIFIER'
                  CHECK (status IN ('A_VERIFIER','EN_ANALYSE','JUSTIFIE','ERREUR_CORRIGEE','ANOMALIE_CONFIRMEE')),
    assigned_to   uuid REFERENCES app_user(id),
    detected_at   timestamptz NOT NULL DEFAULT now(),
    dedup_key     text NOT NULL UNIQUE,         -- une même constatation n'ouvre pas deux dossiers
    FOREIGN KEY (rule_id, rule_version) REFERENCES anomaly_rule(rule_id, rule_version)
);
CREATE TRIGGER trg_anomaly_no_delete BEFORE DELETE ON anomaly
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE TABLE anomaly_review (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    anomaly_id   uuid NOT NULL REFERENCES anomaly(id),
    reviewer_id  uuid NOT NULL REFERENCES app_user(id),
    from_status  text NOT NULL,
    to_status    text NOT NULL,
    comment      text NOT NULL CHECK (length(btrim(comment)) >= 5),
    evidence_file_ids uuid[] NOT NULL DEFAULT '{}',
    reviewed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_review_append_only BEFORE UPDATE OR DELETE ON anomaly_review
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- ================================================================ CHAT (NF-60..64)
CREATE TABLE conversation (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         text NOT NULL CHECK (kind IN ('DIRECT','MISSION','VEHICLE','SERVICE')),
    org_unit_id  uuid NOT NULL REFERENCES organisation_unit(id),
    mission_id   uuid REFERENCES mission(id),
    title        text,
    last_seq     bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (kind <> 'MISSION' OR mission_id IS NOT NULL)
);

CREATE TABLE conversation_participant (
    conversation_id uuid NOT NULL REFERENCES conversation(id),
    user_id         uuid NOT NULL REFERENCES app_user(id),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    left_at         timestamptz,
    last_read_seq   bigint NOT NULL DEFAULT 0,
    muted           boolean NOT NULL DEFAULT false,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE message (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id    uuid NOT NULL REFERENCES conversation(id),
    sender_id          uuid NOT NULL REFERENCES app_user(id),
    client_message_id  text NOT NULL,
    server_seq         bigint NOT NULL,
    body               text NOT NULL CHECK (length(body) BETWEEN 1 AND 8000),
    attachment_ids     uuid[] NOT NULL DEFAULT '{}',
    created_at         timestamptz NOT NULL DEFAULT now(),
    edited_at          timestamptz,
    UNIQUE (sender_id, client_message_id),           -- REC-CHAT-02 : réémission sans doublon
    UNIQUE (conversation_id, server_seq)             -- ordre stable par conversation
);

-- Séquence serveur attribuée sous verrou de la conversation ; refus si l'émetteur n'est pas participant actif.
CREATE FUNCTION nf_message_assign_seq() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_seq bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM conversation_participant
                    WHERE conversation_id = NEW.conversation_id AND user_id = NEW.sender_id AND left_at IS NULL) THEN
        RAISE EXCEPTION 'Chat : l''émetteur n''est pas participant de la conversation'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    UPDATE conversation SET last_seq = last_seq + 1 WHERE id = NEW.conversation_id
        RETURNING last_seq INTO next_seq;
    NEW.server_seq := next_seq;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_message_seq BEFORE INSERT ON message
    FOR EACH ROW EXECUTE FUNCTION nf_message_assign_seq();

-- ================================================================ CATALOGUE ET 3D (extension V5)
CREATE TABLE vehicle_catalog_model (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    make        text NOT NULL,
    model       text NOT NULL,
    generation  text,
    body_type   text,
    UNIQUE (make, model, generation)
);

CREATE TABLE vehicle_catalog_variant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id    uuid NOT NULL REFERENCES vehicle_catalog_model(id),
    year_from   smallint NOT NULL,
    year_to     smallint,
    trim_label  text,
    energy      text,
    CHECK (year_to IS NULL OR year_to >= year_from)
);
ALTER TABLE vehicle ADD CONSTRAINT fk_vehicle_catalog_variant
    FOREIGN KEY (catalog_variant_id) REFERENCES vehicle_catalog_variant(id);

CREATE TABLE asset_license (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id             text NOT NULL,        -- ex. CC-BY-4.0, contrat CAMTEL n°...
    license_version        text,
    source_url             text NOT NULL,
    author                 text NOT NULL,
    attribution_text       text NOT NULL,
    download_proof_sha256  text,
    redistribution_allowed boolean NOT NULL,
    commercial_use_allowed boolean NOT NULL,
    reviewed_by            uuid REFERENCES app_user(id),
    reviewed_at            timestamptz
);

CREATE TABLE vehicle_3d_asset (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id      uuid REFERENCES vehicle_catalog_variant(id),
    model_id        uuid REFERENCES vehicle_catalog_model(id),
    license_id      uuid NOT NULL REFERENCES asset_license(id),
    match_level     text NOT NULL CHECK (match_level IN ('EXACT','APPROXIMATE','GENERIC')),
    has_interior    boolean NOT NULL DEFAULT false,
    format          text NOT NULL CHECK (format IN ('GLB','OBJ')),
    storage_key     text NOT NULL,
    sha256          text NOT NULL,
    size_bytes      bigint NOT NULL CHECK (size_bytes > 0),
    lod             text NOT NULL DEFAULT 'FULL' CHECK (lod IN ('FULL','LIGHT_ANDROID')),
    derived_from_id uuid REFERENCES vehicle_3d_asset(id),     -- conversion GLB -> OBJ versionnée
    status          text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (status IN ('PENDING_REVIEW','PUBLISHED','WITHDRAWN')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (variant_id IS NOT NULL OR model_id IS NOT NULL OR match_level = 'GENERIC')
);

-- Règle cardinale 7 : un actif n'est publié que si sa licence a été revue et autorise la redistribution.
CREATE FUNCTION nf_guard_asset_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE l asset_license%ROWTYPE;
BEGIN
    IF NEW.status = 'PUBLISHED' THEN
        SELECT * INTO l FROM asset_license WHERE id = NEW.license_id;
        IF NOT l.redistribution_allowed OR l.reviewed_at IS NULL THEN
            RAISE EXCEPTION '3D : licence non revue ou redistribution non autorisée — publication refusée'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_asset_publication BEFORE INSERT OR UPDATE OF status ON vehicle_3d_asset
    FOR EACH ROW EXECUTE FUNCTION nf_guard_asset_publication();

CREATE TABLE vehicle_media (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id     uuid NOT NULL REFERENCES vehicle(id),
    kind           text NOT NULL CHECK (kind IN ('PHOTO_ORIGINALE','PHOTO_DETOUREE','RECONSTRUCTION_ESTIMEE')),
    derived_from   uuid REFERENCES vehicle_media(id),
    storage_key    text NOT NULL,
    sha256         text NOT NULL,
    mime_type      text NOT NULL CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    size_bytes     bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),  -- 10 Mo (chap. 17)
    taken_at       timestamptz,
    uploaded_by    uuid NOT NULL REFERENCES app_user(id),
    scan_state     text NOT NULL DEFAULT 'PENDING' CHECK (scan_state IN ('PENDING','CLEAN','REJECTED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- L'original est toujours conservé ; un dérivé référence son original.
    CHECK ((kind = 'PHOTO_ORIGINALE') = (derived_from IS NULL))
);

-- ================================================================ OUTBOX
CREATE TABLE outbox_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate     text NOT NULL,
    aggregate_id  text NOT NULL,
    event_type    text NOT NULL,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz,
    attempts      integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_pending ON outbox_event(id) WHERE published_at IS NULL;
