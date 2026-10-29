-- Imported SQLite evidence is kept intact, outside the public API. No historical receipt or approval is fabricated.
CREATE TABLE legacy_import_batch (
    id uuid PRIMARY KEY,
    manifest jsonb NOT NULL,
    org_unit_id uuid NOT NULL REFERENCES organisation_unit(id),
    imported_by uuid NOT NULL REFERENCES app_user(id),
    imported_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE legacy_import_record (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES legacy_import_batch(id),
    source_db text NOT NULL,
    source_table text NOT NULL,
    source_key text NOT NULL,
    payload jsonb NOT NULL,
    sha256 text NOT NULL CHECK (length(sha256) = 64),
    UNIQUE(source_db, source_table, source_key)
);
CREATE TRIGGER trg_legacy_record_immutable BEFORE UPDATE OR DELETE ON legacy_import_record
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();
CREATE TRIGGER trg_legacy_batch_immutable BEFORE UPDATE OR DELETE ON legacy_import_batch
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- Historical expenses/maintenance/fuel lack the modern evidence needed for a validated workflow.
-- They remain visible and count towards costs, explicitly marked as imported, without weakening workflow constraints.
CREATE TABLE legacy_operation (
    id uuid PRIMARY KEY REFERENCES legacy_import_record(id),
    org_unit_id uuid NOT NULL REFERENCES organisation_unit(id),
    vehicle_id uuid REFERENCES vehicle(id),
    kind text NOT NULL CHECK (kind IN ('FUEL','MAINTENANCE','EXPENSE')),
    occurred_on date NOT NULL,
    category_code text NOT NULL REFERENCES maintenance_category(code),
    label text NOT NULL,
    amount_xaf numeric(16,2) NOT NULL CHECK (amount_xaf >= 0),
    liters numeric(12,3) CHECK (liters >= 0),
    odometer_km numeric(10,1) CHECK (odometer_km >= 0),
    provider text,
    notes text
);
CREATE INDEX ix_legacy_operation_org_date ON legacy_operation(org_unit_id, occurred_on);
CREATE TRIGGER trg_legacy_operation_immutable BEFORE UPDATE OR DELETE ON legacy_operation
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

CREATE VIEW v_cost_entry AS
SELECT f.id, v.org_unit_id, f.vehicle_id, (f.filled_at AT TIME ZONE 'Africa/Douala')::date AS occurred_on,
       'FUEL'::text AS kind, 'CARBURANT'::text AS category_code, f.amount AS amount_xaf, f.liters,
       false AS imported
  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
UNION ALL
SELECT m.id, v.org_unit_id, m.vehicle_id,
       (coalesce(m.immobilised_to, m.created_at) AT TIME ZONE 'Africa/Douala')::date,
       'MAINTENANCE', m.category_code, m.cost_xaf, 0, false
  FROM maintenance_order m JOIN vehicle v ON v.id = m.vehicle_id
 WHERE m.status = 'CLOTUREE' AND m.cost_xaf IS NOT NULL
UNION ALL
SELECT o.id, v.org_unit_id, o.vehicle_id, o.done_on, 'OIL', 'VIDANGE', o.cost_xaf, 0, false
  FROM oil_change o JOIN vehicle v ON v.id = o.vehicle_id
 WHERE o.maintenance_order_id IS NULL AND o.cost_xaf IS NOT NULL
UNION ALL
SELECT l.id, l.org_unit_id, l.vehicle_id, l.occurred_on, l.kind,
       CASE WHEN l.kind = 'FUEL' THEN 'CARBURANT' ELSE l.category_code END, l.amount_xaf, coalesce(l.liters, 0), true
  FROM legacy_operation l;
