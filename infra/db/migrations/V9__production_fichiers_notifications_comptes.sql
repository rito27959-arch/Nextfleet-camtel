-- V9 : passage en production.
--  * Stockage privé des pièces justificatives (ordres de maintenance, reçus carburant, documents, pièces jointes du chat).
--  * État « lu » des notifications par utilisateur (les notifications elles-mêmes sont calculées à la demande).
--  * Changement de mot de passe imposé après création ou réinitialisation par l'administration.
--  * Canal temps réel des messages (LISTEN/NOTIFY), sur le modèle de V8 pour les positions.

-- ---------------------------------------------------------------- fichiers
CREATE TABLE stored_file (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose       text NOT NULL CHECK (purpose IN ('CHAT','MAINTENANCE','FUEL_RECEIPT','VEHICLE_DOCUMENT','DRIVER_DOCUMENT','INSPECTION')),
    org_unit_id   uuid NOT NULL REFERENCES organisation_unit(id),
    original_name text NOT NULL CHECK (length(btrim(original_name)) BETWEEN 1 AND 200),
    mime_type     text NOT NULL CHECK (mime_type IN ('application/pdf','image/jpeg','image/png','image/webp')),
    size_bytes    bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content       bytea NOT NULL,
    uploaded_by   uuid NOT NULL REFERENCES app_user(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_file_size_matches CHECK (octet_length(content) = size_bytes)
);
CREATE INDEX ix_stored_file_uploader ON stored_file(uploaded_by, created_at DESC);

-- Les colonnes *_file_id existantes pointent désormais vers ce stockage.
ALTER TABLE maintenance_order ADD CONSTRAINT fk_maintenance_proof FOREIGN KEY (proof_file_id) REFERENCES stored_file(id);
ALTER TABLE fuel_fill         ADD CONSTRAINT fk_fuel_fill_receipt FOREIGN KEY (receipt_file_id) REFERENCES stored_file(id);
ALTER TABLE vehicle_document  ADD CONSTRAINT fk_vehicle_document_file FOREIGN KEY (file_id) REFERENCES stored_file(id);
ALTER TABLE driver_license    ADD CONSTRAINT fk_driver_license_file FOREIGN KEY (file_id) REFERENCES stored_file(id);

-- Pièces jointes : au plus 5 par message, chacune doit exister et avoir été déposée par l'expéditeur pour le chat.
ALTER TABLE message ADD CONSTRAINT ck_message_attachments_count CHECK (cardinality(attachment_ids) <= 5);
CREATE FUNCTION nf_check_message_attachments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF cardinality(NEW.attachment_ids) > 0 AND EXISTS (
        SELECT 1 FROM unnest(NEW.attachment_ids) a(id)
         WHERE NOT EXISTS (SELECT 1 FROM stored_file f
                            WHERE f.id = a.id AND f.purpose = 'CHAT' AND f.uploaded_by = NEW.sender_id)) THEN
        RAISE EXCEPTION 'CHAT-05 : pièce jointe inconnue ou déposée par un autre utilisateur' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_message_attachments BEFORE INSERT OR UPDATE OF attachment_ids ON message
    FOR EACH ROW EXECUTE FUNCTION nf_check_message_attachments();

-- ---------------------------------------------------------------- notifications
CREATE TABLE notification_read (
    user_id          uuid NOT NULL REFERENCES app_user(id),
    notification_key text NOT NULL CHECK (length(notification_key) BETWEEN 3 AND 200),
    read_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, notification_key)
);

-- ---------------------------------------------------------------- comptes
ALTER TABLE app_user ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
                     ADD COLUMN password_changed_at  timestamptz,
                     ADD COLUMN phone                text;

-- ---------------------------------------------------------------- messagerie temps réel
CREATE FUNCTION nf_notify_message() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('nf_message', json_build_object(
        'conversationId', NEW.conversation_id, 'serverSeq', NEW.server_seq, 'senderId', NEW.sender_id)::text);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_message_notify AFTER INSERT ON message FOR EACH ROW EXECUTE FUNCTION nf_notify_message();

-- ---------------------------------------------------------------- index utiles aux écrans de production
CREATE INDEX IF NOT EXISTS ix_maintenance_order_vehicle ON maintenance_order(vehicle_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_maintenance_order_status  ON maintenance_order(status);
CREATE INDEX IF NOT EXISTS ix_fuel_fill_vehicle_time    ON fuel_fill(vehicle_id, filled_at DESC);
CREATE INDEX IF NOT EXISTS ix_fuel_order_status         ON fuel_order(status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_anomaly_status            ON anomaly(status, detected_at DESC);
CREATE INDEX IF NOT EXISTS ix_vehicle_document_vehicle  ON vehicle_document(vehicle_id, doc_type, valid_until DESC);
