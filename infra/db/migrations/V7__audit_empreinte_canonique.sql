-- V7 : empreinte du journal indépendante du fuseau de la session.
-- La V1 hachait occurred_at::text, dont le rendu dépend du paramètre TimeZone de la session qui insère :
-- une vérification depuis une autre session pouvait conclure à tort à une rupture. À partir de la V7, l'horodatage
-- est haché sous une forme canonique UTC à la microseconde. Les lignes existantes gardent leur formule (version 1).
ALTER TABLE audit_event ADD COLUMN hash_version smallint NOT NULL DEFAULT 1 CHECK (hash_version IN (1, 2));
ALTER TABLE audit_event ALTER COLUMN hash_version SET DEFAULT 2;

CREATE FUNCTION nf_audit_payload(p_version smallint, p_prev bytea, p_at timestamptz, p_actor uuid, p_action text,
                                 p_type text, p_id text, p_reason text, p_old jsonb, p_new jsonb)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(encode(p_prev,'hex'),'') || '|' ||
           CASE WHEN p_version = 1 THEN p_at::text
                ELSE to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END || '|' ||
           coalesce(p_actor::text,'') || '|' || p_action || '|' || p_type || '|' || p_id || '|' ||
           coalesce(p_reason,'') || '|' || coalesce(p_old::text,'') || '|' || coalesce(p_new::text,'');
$$;

CREATE OR REPLACE FUNCTION nf_audit_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev bytea;
BEGIN
    PERFORM pg_advisory_xact_lock(424242);                       -- sérialise le chaînage
    SELECT row_hash INTO prev FROM audit_event ORDER BY id DESC LIMIT 1;
    NEW.prev_hash := prev;
    NEW.hash_version := 2;
    NEW.row_hash := digest(nf_audit_payload(NEW.hash_version, prev, NEW.occurred_at, NEW.actor_id, NEW.action,
                                            NEW.object_type, NEW.object_id, NEW.reason, NEW.old_value, NEW.new_value), 'sha256');
    RETURN NEW;
END $$;

-- Vérification complète : chaînage (prev_hash = empreinte précédente) ET contenu (empreinte recalculée).
-- Les lignes de version 1 ne sont vérifiables que dans le fuseau de la session qui les a écrites.
CREATE FUNCTION nf_audit_verify() RETURNS TABLE(events bigint, first_broken_id bigint) LANGUAGE sql STABLE AS $$
    WITH c AS (
        SELECT id, row_hash, prev_hash, lag(row_hash) OVER (ORDER BY id) AS expected_prev,
               digest(nf_audit_payload(hash_version, prev_hash, occurred_at, actor_id, action, object_type, object_id,
                                       reason, old_value, new_value), 'sha256') AS recomputed
          FROM audit_event)
    SELECT count(*), min(id) FILTER (WHERE row_hash <> recomputed OR prev_hash IS DISTINCT FROM expected_prev) FROM c;
$$;
