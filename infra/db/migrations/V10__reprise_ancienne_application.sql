-- NexFleet CAMTEL — V10 : reprise des données de l'ancienne application (base SQLite « parc.db »).
-- Traçabilité complète : chaque ligne reprise est rattachée à son identifiant d'origine (aucun doublon au réimport),
-- ce qui n'a pas d'équivalent dans NexFleet est conservé tel quel dans une archive en lecture seule.
-- Les secrets de l'ancienne application (clés d'API, empreintes de mots de passe) ne sont jamais repris.

CREATE TABLE legacy_import (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source        text NOT NULL,                      -- « ancien-nexfleet »
    file_name     text,
    file_sha256   text NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    imported_by   uuid NOT NULL REFERENCES app_user(id),
    imported_at   timestamptz NOT NULL DEFAULT now(),
    summary       jsonb NOT NULL
);

-- Références à l'import vérifiées en fin de transaction : le résumé n'est connu (et l'import inscrit) qu'une fois tout repris.
-- Ligne d'origine → ligne NexFleet (target_id NULL : écartée ou seulement archivée, motif dans l'archive ou le résumé)
CREATE TABLE legacy_map (
    source        text NOT NULL,
    table_name    text NOT NULL,
    source_id     bigint NOT NULL,
    target_id     uuid,
    import_id     uuid NOT NULL REFERENCES legacy_import(id) DEFERRABLE INITIALLY DEFERRED,
    PRIMARY KEY (source, table_name, source_id)
);

-- Contenu d'origine sans équivalent (journal de l'ancienne application, budget hors parc automobile, comptes sans
-- empreinte de mot de passe, noms de photos…) : conservé pour la traçabilité, jamais modifié.
CREATE TABLE legacy_archive (
    import_id     uuid NOT NULL REFERENCES legacy_import(id) DEFERRABLE INITIALLY DEFERRED,
    table_name    text NOT NULL,
    source_id     bigint NOT NULL,
    payload       jsonb NOT NULL,
    PRIMARY KEY (import_id, table_name, source_id)
);

CREATE TRIGGER trg_legacy_import_immutable BEFORE UPDATE OR DELETE ON legacy_import
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();
CREATE TRIGGER trg_legacy_map_immutable BEFORE UPDATE OR DELETE ON legacy_map
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();
CREATE TRIGGER trg_legacy_archive_immutable BEFORE UPDATE OR DELETE ON legacy_archive
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- Interventions historiques : l'ancienne application ne gardait ni justificatif ni visa technique. Une intervention
-- reprise est close telle quelle, marquée comme reprise ; toute intervention saisie dans NexFleet reste soumise à la
-- règle complète (kilométrage, travaux, coût, justificatif, visa).
ALTER TABLE maintenance_order ADD COLUMN legacy_import_id uuid REFERENCES legacy_import(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE maintenance_order DROP CONSTRAINT ck_closure_complete;
ALTER TABLE maintenance_order ADD CONSTRAINT ck_closure_complete CHECK (status <> 'CLOTUREE'
    OR (legacy_import_id IS NOT NULL AND cost_xaf IS NOT NULL AND length(btrim(coalesce(work_done,''))) > 0)
    OR (odometer_km IS NOT NULL AND cost_xaf IS NOT NULL AND length(btrim(coalesce(work_done,''))) > 0
        AND proof_file_id IS NOT NULL AND technical_visa_by IS NOT NULL));

CREATE INDEX ix_legacy_map_import ON legacy_map(import_id);
