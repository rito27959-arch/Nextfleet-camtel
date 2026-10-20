-- NexFleet CAMTEL — V4 : parité avec l'ancien dépôt rito27959-arch/nexfleet (voir docs/REGISTRE_PARITE.md)
-- Reprend les données utiles de l'ancien schéma SQLite sans ses défauts : pas de suppression physique,
-- montants décimaux, dates typées, historique au lieu d'écrasement.

-- ---------------------------------------------------------------- véhicule : champs de l'ancien schéma
ALTER TABLE vehicle
    ADD COLUMN direction            text,          -- « direction » de l'ancien schéma
    ADD COLUMN site_label           text,          -- « affectation » libre (l'affectation formelle reste vehicle_assignment)
    ADD COLUMN fiscal_hp            smallint CHECK (fiscal_hp BETWEEN 1 AND 99),   -- « cv »
    ADD COLUMN registration_card_no text,          -- « carte_grise »
    ADD COLUMN declared_lat         double precision CHECK (declared_lat BETWEEN -90 AND 90),
    ADD COLUMN declared_lng         double precision CHECK (declared_lng BETWEEN -180 AND 180),
    ADD COLUMN declared_at          timestamptz;    -- NF-19 : position DÉCLARÉE, horodatée, jamais « en direct »
ALTER TABLE vehicle ADD CONSTRAINT ck_declared_position
    CHECK ((declared_lat IS NULL) = (declared_lng IS NULL) AND ((declared_lat IS NULL) OR declared_at IS NOT NULL));

-- ---------------------------------------------------------------- conducteur : CNI et contact
ALTER TABLE driver
    ADD COLUMN email          text,
    ADD COLUMN id_card_number text,
    ADD COLUMN id_card_expires_on date;

-- ---------------------------------------------------------------- contrôles 12 points (NF-13)
CREATE TABLE inspection_point (
    code        text PRIMARY KEY,
    position    smallint NOT NULL UNIQUE,
    label       text NOT NULL,
    hint        text NOT NULL,
    active      boolean NOT NULL DEFAULT true
);
INSERT INTO inspection_point(code, position, label, hint) VALUES
 ('CARROSSERIE', 1, 'Carrosserie et état extérieur', 'peinture, corrosion, pare-chocs'),
 ('PNEUMATIQUES', 2, 'Pneumatiques et pression', 'usure, pression, profondeur de gomme'),
 ('FREINS', 3, 'Freins et plaquettes', 'circuit, plaquettes, efficacité'),
 ('ECLAIRAGE', 4, 'Éclairage et signalisation', 'phares, feux, clignotants, klaxon'),
 ('NIVEAUX', 5, 'Niveaux (huile, refroidissement)', 'niveaux moteur et liquides'),
 ('BATTERIE', 6, 'Batterie et démarrage', 'charge, démarrage, connectique'),
 ('ESSUIE_GLACES', 7, 'Essuie-glaces et lave-glace', 'balais, réservoir, jets'),
 ('SUSPENSION', 8, 'Suspension et direction', 'amortisseurs, jeu, géométrie'),
 ('DOCUMENTS', 9, 'Documents de bord', 'assurance, vignette, contrôle technique'),
 ('SECURITE', 10, 'Sécurité (triangle, trousse)', 'équipement obligatoire'),
 ('CLIMATISATION', 11, 'Climatisation / chauffage', 'fonctionnement global'),
 ('SECOURS', 12, 'Roue de secours / kit', 'présence et état');

CREATE TABLE inspection (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id    uuid NOT NULL REFERENCES vehicle(id),
    driver_id     uuid REFERENCES driver(id),
    mission_id    uuid REFERENCES mission(id),
    kind          text NOT NULL CHECK (kind IN ('PERIODIQUE','AVANT_MISSION','APRES_MISSION','INOPINE')),
    inspected_at  timestamptz NOT NULL,
    odometer_km   numeric(10,1) CHECK (odometer_km >= 0),
    result        text NOT NULL CHECK (result IN ('CONFORME','RESERVES','NON_CONFORME')),
    notes         text,
    author_id     uuid NOT NULL REFERENCES app_user(id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_inspection_no_delete BEFORE DELETE ON inspection
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE TABLE inspection_item (
    inspection_id uuid NOT NULL REFERENCES inspection(id),
    point_code    text NOT NULL REFERENCES inspection_point(code),
    state         text NOT NULL CHECK (state IN ('OK','A_SURVEILLER','DEFAUT')),
    critical      boolean NOT NULL DEFAULT false,
    comment       text,
    PRIMARY KEY (inspection_id, point_code),
    CHECK (state <> 'DEFAUT' OR length(btrim(coalesce(comment,''))) > 0)   -- un défaut est décrit
);

-- ---------------------------------------------------------------- maintenance (NF-14)
CREATE TABLE maintenance_category (
    code   text PRIMARY KEY,
    label  text NOT NULL
);
INSERT INTO maintenance_category(code, label) VALUES
 ('MOTEUR','Moteur'), ('FREINAGE','Freinage'), ('POMPES','Pompes et injection'), ('ADMINISTRATIF','Administratif'),
 ('VIDANGE','Vidange'), ('PNEUMATIQUE','Pneumatique'), ('TOLERIE','Tôlerie'), ('BATTERIE','Batterie'),
 ('ELECTRICITE','Électricité'), ('AUTRE','Autre');

CREATE TABLE maintenance_order (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id        uuid NOT NULL REFERENCES vehicle(id),
    category_code     text NOT NULL REFERENCES maintenance_category(code),
    title             text NOT NULL,
    status            text NOT NULL DEFAULT 'DEMANDE'
                      CHECK (status IN ('DEMANDE','QUALIFIEE','PLANIFIEE','EN_COURS','CLOTUREE','ANNULEE')),
    garage_supplier_id uuid REFERENCES supplier(id),
    garage_label      text,
    immobilised_from  timestamptz,
    immobilised_to    timestamptz,
    odometer_km       numeric(10,1) CHECK (odometer_km >= 0),
    cost_xaf          numeric(16,2) CHECK (cost_xaf >= 0),
    work_done         text,
    proof_file_id     uuid,
    technical_visa_by uuid REFERENCES app_user(id),
    requested_by      uuid NOT NULL REFERENCES app_user(id),
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (immobilised_to IS NULL OR immobilised_to >= immobilised_from),
    -- Clôture : kilométrage, travaux, coût, justificatif et visa technique exigés (chap. 04)
    CONSTRAINT ck_closure_complete CHECK (status <> 'CLOTUREE' OR (odometer_km IS NOT NULL AND cost_xaf IS NOT NULL
        AND length(btrim(coalesce(work_done,''))) > 0 AND proof_file_id IS NOT NULL AND technical_visa_by IS NOT NULL))
);
CREATE INDEX ix_maintenance_vehicle ON maintenance_order(vehicle_id, created_at);
CREATE TRIGGER trg_maintenance_no_delete BEFORE DELETE ON maintenance_order
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- ---------------------------------------------------------------- vidanges (NF-15)
CREATE TABLE oil_change (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id             uuid NOT NULL REFERENCES vehicle(id),
    maintenance_order_id   uuid REFERENCES maintenance_order(id),   -- si présent, le coût n'est compté qu'une fois
    done_on                date NOT NULL,
    odometer_km            numeric(10,1) NOT NULL CHECK (odometer_km >= 0),
    oil_label              text,
    oil_liters             numeric(6,2) CHECK (oil_liters >= 0),
    oil_filter             boolean NOT NULL DEFAULT false,
    fuel_filter            boolean NOT NULL DEFAULT false,
    air_filter             boolean NOT NULL DEFAULT false,
    transmission_oil       boolean NOT NULL DEFAULT false,
    coolant                boolean NOT NULL DEFAULT false,
    washer_fluid           boolean NOT NULL DEFAULT false,
    interval_km            integer CHECK (interval_km > 0),
    interval_months        smallint CHECK (interval_months > 0),
    next_due_km            numeric(10,1) GENERATED ALWAYS AS (odometer_km + interval_km) STORED,
    cost_xaf               numeric(16,2) CHECK (cost_xaf >= 0),
    garage_label           text,
    author_id              uuid NOT NULL REFERENCES app_user(id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    -- NF-15 : une vidange rattachée à un ordre de maintenance ne porte pas de coût propre (pas de double comptage)
    CONSTRAINT ck_no_double_cost CHECK (maintenance_order_id IS NULL OR cost_xaf IS NULL)
);

-- ---------------------------------------------------------------- pneumatiques (NF-16)
CREATE TABLE tire (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_label  text,
    brand         text,
    size          text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- Chaque événement est une ligne : une permutation ne détruit pas l'origine (réception NF-16).
CREATE TABLE tire_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tire_id       uuid NOT NULL REFERENCES tire(id),
    vehicle_id    uuid NOT NULL REFERENCES vehicle(id),
    kind          text NOT NULL CHECK (kind IN ('MONTAGE','PERMUTATION','DEMONTAGE','REMPLACEMENT','CONTROLE')),
    position      text CHECK (position IN ('AVG','AVD','ARG','ARD','ARG_INT','ARD_INT','SECOURS')),
    condition     text CHECK (condition IN ('NEUF','BON','USE','A_REMPLACER')),
    occurred_on   date NOT NULL,
    odometer_km   numeric(10,1),
    note          text,
    author_id     uuid NOT NULL REFERENCES app_user(id)
);
CREATE TRIGGER trg_tire_event_append_only BEFORE UPDATE OR DELETE ON tire_event
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- Position courante = dernier événement de montage/permutation sans démontage ultérieur.
CREATE VIEW v_tire_current_position AS
SELECT DISTINCT ON (e.tire_id) e.tire_id, e.vehicle_id, e.position, e.condition, e.occurred_on
  FROM tire_event e
 ORDER BY e.tire_id, e.occurred_on DESC, e.id DESC;

-- ---------------------------------------------------------------- pièces du véhicule (NF-18)
CREATE TABLE vehicle_file (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id    uuid NOT NULL REFERENCES vehicle(id),
    label         text NOT NULL,
    original_name text NOT NULL,
    storage_key   text NOT NULL UNIQUE,         -- clé de stockage privé, jamais une URL publique
    sha256        text NOT NULL,
    mime_type     text NOT NULL CHECK (mime_type IN ('application/pdf','image/jpeg','image/png','image/webp')),
    size_bytes    bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    scan_state    text NOT NULL DEFAULT 'PENDING' CHECK (scan_state IN ('PENDING','CLEAN','REJECTED')),
    uploaded_by   uuid NOT NULL REFERENCES app_user(id),
    archived_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- budget : plan d'achat annuel (NF-21)
CREATE TABLE budget_plan_line (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fiscal_year      smallint NOT NULL CHECK (fiscal_year BETWEEN 2000 AND 2100),
    org_unit_id      uuid NOT NULL REFERENCES organisation_unit(id),
    category_code    text NOT NULL REFERENCES maintenance_category(code),
    activity         text,
    task             text NOT NULL,
    q1_amount        numeric(16,2) NOT NULL DEFAULT 0 CHECK (q1_amount >= 0),
    q2_amount        numeric(16,2) NOT NULL DEFAULT 0 CHECK (q2_amount >= 0),
    q3_amount        numeric(16,2) NOT NULL DEFAULT 0 CHECK (q3_amount >= 0),
    q4_amount        numeric(16,2) NOT NULL DEFAULT 0 CHECK (q4_amount >= 0),
    annual_amount    numeric(16,2) GENERATED ALWAYS AS (q1_amount + q2_amount + q3_amount + q4_amount) STORED,
    source_file      text NOT NULL,
    source_row       integer NOT NULL,
    import_batch_id  uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (fiscal_year, org_unit_id, source_file, source_row)    -- NF-26 : un réimport ne crée pas de doublon
);

-- ---------------------------------------------------------------- échéances unifiées (NF-11, ancien all_alerts)
-- Une même échéance produit la même date et le même niveau partout (réception NF-11).
CREATE VIEW v_due_date AS
SELECT 'VEHICULE'::text AS subject_kind, d.vehicle_id AS subject_id, v.org_unit_id,
       v.registration AS subject_label, d.doc_type AS due_kind, d.valid_until AS due_on
  FROM vehicle_document d JOIN vehicle v ON v.id = d.vehicle_id
 WHERE v.archived_at IS NULL
   AND d.valid_until = (SELECT max(d2.valid_until) FROM vehicle_document d2
                         WHERE d2.vehicle_id = d.vehicle_id AND d2.doc_type = d.doc_type)
UNION ALL
SELECT 'CONDUCTEUR', l.driver_id, dr.org_unit_id, dr.full_name, 'PERMIS', max(l.expires_on)
  FROM driver_license l JOIN driver dr ON dr.id = l.driver_id
 WHERE dr.archived_at IS NULL
 GROUP BY l.driver_id, dr.org_unit_id, dr.full_name
UNION ALL
SELECT 'CONDUCTEUR', dr.id, dr.org_unit_id, dr.full_name, 'CNI', dr.id_card_expires_on
  FROM driver dr
 WHERE dr.archived_at IS NULL AND dr.id_card_expires_on IS NOT NULL;

-- Niveau calculé à une date de référence et un seuil approuvé (seuil par défaut de l'ancien produit : 30 jours).
CREATE FUNCTION nf_due_dates(ref_date date, threshold_days integer)
RETURNS TABLE(subject_kind text, subject_id uuid, org_unit_id uuid, subject_label text, due_kind text,
              due_on date, days_left integer, level text)
LANGUAGE sql STABLE AS $$
    SELECT subject_kind, subject_id, org_unit_id, subject_label, due_kind, due_on,
           (due_on - ref_date) AS days_left,
           CASE WHEN due_on < ref_date THEN 'EXPIRE'
                WHEN due_on - ref_date <= threshold_days THEN 'A_ECHEANCE'
                ELSE 'A_JOUR' END AS level
      FROM v_due_date
     ORDER BY due_on, subject_label;
$$;
