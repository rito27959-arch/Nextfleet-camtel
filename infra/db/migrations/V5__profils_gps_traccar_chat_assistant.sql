-- NexFleet CAMTEL — V5 (lot 2) : compléments des six profils, registre des sources GPS (Traccar),
-- journal des rejets de collecte, états de remise du chat, activation contrôlée et journal de l'assistant IA.

-- ================================================================ PROFILS : permissions complémentaires
INSERT INTO permission(code, label) VALUES
 ('maintenance:write','Saisir interventions et contrôles'),
 ('maintenance:validate','Valider techniquement une intervention'),
 ('cost:read','Consulter coûts et dépenses'),
 ('report:export','Exporter des rapports'),
 ('assistant:use','Interroger l''assistant (lecture seule)');

INSERT INTO role_permission(role_code, permission_code) VALUES
 ('FLEET_MANAGER','cost:read'),('FLEET_MANAGER','report:export'),('FLEET_MANAGER','assistant:use'),
 ('MAINTENANCE','maintenance:write'),('MAINTENANCE','maintenance:validate'),
 ('CONTROLLER','cost:read'),('CONTROLLER','report:export'),('CONTROLLER','assistant:use'),
 ('AUDITOR','cost:read'),('AUDITOR','report:export');
-- ADMIN reste sans permission métier ; AUDITOR n'a ni messagerie ni écriture.

-- Fonction utilitaire : l'utilisateur porte-t-il la permission via un rôle actif ?
CREATE FUNCTION nf_has_permission(p_user uuid, p_permission text, p_at timestamptz DEFAULT now())
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM user_role_grant g JOIN role_permission rp ON rp.role_code = g.role_code
         WHERE g.user_id = p_user AND rp.permission_code = p_permission AND g.revoked_at IS NULL
           AND g.valid_from <= p_at AND (g.valid_until IS NULL OR g.valid_until > p_at));
$$;

-- ================================================================ GPS : sources et rejets
-- Registre des émetteurs connus de Traccar (uniqueId) : téléphone Android (phase 1) ou boîtier (phase 2).
CREATE TABLE tracking_source (
    unique_id      text PRIMARY KEY CHECK (unique_id ~ '^[A-Za-z0-9._-]{3,64}$'),
    kind           text NOT NULL CHECK (kind IN ('ANDROID','TRACKER')),
    label          text NOT NULL,
    vehicle_id     uuid REFERENCES vehicle(id),          -- boîtier fixé à un véhicule ; NULL pour un téléphone
    registered_by  uuid NOT NULL REFERENCES app_user(id),
    registered_at  timestamptz NOT NULL DEFAULT now(),
    disabled_at    timestamptz,
    CHECK (kind <> 'TRACKER' OR vehicle_id IS NOT NULL)
);

-- Rejets de collecte : on garde la PREUVE du refus sans conserver la position (minimisation, NF-70).
CREATE TABLE gps_ingest_rejection (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_device_id text NOT NULL,
    recorded_at      timestamptz,
    received_at      timestamptz NOT NULL DEFAULT now(),
    channel          text NOT NULL CHECK (channel IN ('TRACCAR_FORWARD','ANDROID_GATEWAY')),
    reason_code      text NOT NULL CHECK (reason_code IN ('SOURCE_INCONNUE','AUCUNE_MISSION_ACTIVE','HORS_PERIODE',
                                                          'AUTORISATION_REVOQUEE','DONNEE_INVALIDE','DOUBLON'))
);
CREATE TRIGGER trg_gps_rejection_append_only BEFORE UPDATE OR DELETE ON gps_ingest_rejection
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- ================================================================ CHAT : états de remise (NF-62)
ALTER TABLE conversation_participant ADD COLUMN last_delivered_seq bigint NOT NULL DEFAULT 0;
ALTER TABLE conversation_participant ADD CONSTRAINT ck_read_after_delivered CHECK (last_read_seq <= last_delivered_seq);

-- Curseurs monotones : un accusé ne recule jamais ; « lu » implique « distribué ».
CREATE FUNCTION nf_participant_cursors() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.last_read_seq := greatest(OLD.last_read_seq, NEW.last_read_seq);
    NEW.last_delivered_seq := greatest(OLD.last_delivered_seq, NEW.last_delivered_seq, NEW.last_read_seq);
    IF NEW.last_delivered_seq > (SELECT last_seq FROM conversation WHERE id = NEW.conversation_id) THEN
        RAISE EXCEPTION 'Chat : accusé au-delà du dernier message' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_participant_cursors BEFORE UPDATE OF last_read_seq, last_delivered_seq ON conversation_participant
    FOR EACH ROW EXECUTE FUNCTION nf_participant_cursors();

-- Correctif de la V3 : une réémission (même émetteur, même clientMessageId) ne doit pas consommer de numéro.
-- Verrou de la conversation PUIS contrôle du doublon PUIS incrément : séquence sans trou, y compris en concurrence.
-- La réémission est ignorée sans erreur (idempotence) ; le client relit le message existant.
CREATE OR REPLACE FUNCTION nf_message_assign_seq() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_seq bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM conversation_participant
                    WHERE conversation_id = NEW.conversation_id AND user_id = NEW.sender_id AND left_at IS NULL) THEN
        RAISE EXCEPTION 'Chat : l''émetteur n''est pas participant de la conversation'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM 1 FROM conversation WHERE id = NEW.conversation_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM message WHERE sender_id = NEW.sender_id AND client_message_id = NEW.client_message_id) THEN
        RETURN NULL;
    END IF;
    UPDATE conversation SET last_seq = last_seq + 1 WHERE id = NEW.conversation_id RETURNING last_seq INTO next_seq;
    NEW.server_seq := next_seq;
    RETURN NEW;
END $$;

-- La messagerie exige la permission chat:use au moment de l'envoi (en plus de la participation).
CREATE FUNCTION nf_message_requires_permission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT nf_has_permission(NEW.sender_id, 'chat:use') THEN
        RAISE EXCEPTION 'Chat : permission chat:use absente pour l''émetteur' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_message_permission BEFORE INSERT ON message
    FOR EACH ROW EXECUTE FUNCTION nf_message_requires_permission();

-- ================================================================ ASSISTANT IA (lecture seule)
-- Décisions d'activation en ajout seul. Activer exige une référence de validation de sécurité (DS / RSSI)
-- et un signataire distinct de l'administrateur qui applique la décision.
CREATE TABLE assistant_activation (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enabled              boolean NOT NULL,
    security_review_ref  text,
    approved_by_name     text,
    decided_by           uuid NOT NULL REFERENCES app_user(id),
    decided_at           timestamptz NOT NULL DEFAULT now(),
    reason               text NOT NULL CHECK (length(btrim(reason)) >= 5),
    CONSTRAINT ck_activation_requires_review CHECK (NOT enabled OR
        (security_review_ref IS NOT NULL AND length(btrim(security_review_ref)) >= 3
         AND approved_by_name IS NOT NULL AND length(btrim(approved_by_name)) >= 3))
);
CREATE TRIGGER trg_activation_append_only BEFORE UPDATE OR DELETE ON assistant_activation
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE FUNCTION nf_activation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT nf_has_permission(NEW.decided_by, 'admin:settings') THEN
        RAISE EXCEPTION 'Assistant : seule l''administration technique applique une décision d''activation'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.enabled AND lower(btrim(NEW.approved_by_name)) =
       (SELECT lower(btrim(display_name)) FROM app_user WHERE id = NEW.decided_by) THEN
        RAISE EXCEPTION 'Assistant : le signataire de la validation doit être distinct de l''administrateur'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_activation_guard BEFORE INSERT ON assistant_activation
    FOR EACH ROW EXECUTE FUNCTION nf_activation_guard();

-- État courant : la dernière décision ; désactivé tant qu'aucune décision n'existe.
CREATE FUNCTION nf_assistant_enabled() RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT enabled FROM assistant_activation ORDER BY decided_at DESC, id DESC LIMIT 1), false);
$$;

-- Journal des interrogations : question, intention, sources citées ; jamais de réponse servie sans trace.
CREATE TABLE assistant_run (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(id),
    asked_at        timestamptz NOT NULL DEFAULT now(),
    question        text NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
    intent          text NOT NULL,
    refused         boolean NOT NULL,
    refusal_reason  text,
    source_refs     text[] NOT NULL DEFAULT '{}',
    engine          text NOT NULL DEFAULT 'RULES_FR_V1',
    CHECK (refused = (refusal_reason IS NOT NULL))
);
CREATE TRIGGER trg_assistant_run_append_only BEFORE UPDATE OR DELETE ON assistant_run
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE FUNCTION nf_assistant_run_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT NEW.refused AND NOT nf_assistant_enabled() THEN
        RAISE EXCEPTION 'Assistant désactivé : aucune réponse ne peut être servie' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT NEW.refused AND NOT nf_has_permission(NEW.user_id, 'assistant:use', NEW.asked_at) THEN
        RAISE EXCEPTION 'Assistant : permission assistant:use absente' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_assistant_run_guard BEFORE INSERT ON assistant_run
    FOR EACH ROW EXECUTE FUNCTION nf_assistant_run_guard();
