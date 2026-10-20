-- NexFleet CAMTEL — V2 : chaîne carburant (NF-40 à NF-45, CAR-01/02/04, RG-06)
-- Montants : numeric(16,2) XAF ; litres : numeric(9,2) ; jamais de flottant.

CREATE TABLE supplier (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('STATION','GARAGE','AUTRE')),
    is_demo     boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_supplier_demo BEFORE INSERT OR UPDATE ON supplier
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

-- NF-40 : bon unique, demandeur ≠ valideur, solde protégé.
CREATE TABLE fuel_order (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number        text NOT NULL UNIQUE,
    vehicle_id          uuid NOT NULL REFERENCES vehicle(id),
    supplier_id         uuid NOT NULL REFERENCES supplier(id),
    org_unit_id         uuid NOT NULL REFERENCES organisation_unit(id),
    requested_by        uuid NOT NULL REFERENCES app_user(id),
    approved_by         uuid REFERENCES app_user(id),
    approved_at         timestamptz,
    authorized_liters   numeric(9,2)  NOT NULL CHECK (authorized_liters > 0),
    authorized_amount   numeric(16,2) NOT NULL CHECK (authorized_amount > 0),
    delivered_liters    numeric(9,2)  NOT NULL DEFAULT 0 CHECK (delivered_liters >= 0),
    delivered_amount    numeric(16,2) NOT NULL DEFAULT 0 CHECK (delivered_amount >= 0),
    valid_until         date NOT NULL,
    status              text NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','PARTIALLY_CONSUMED','SETTLED','CANCELLED','EXPIRED')),
    reason              text,
    is_demo             boolean NOT NULL DEFAULT false,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_no_self_approval CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_approved_fields CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_liters_balance CHECK (delivered_liters <= authorized_liters),
    CONSTRAINT ck_amount_balance CHECK (delivered_amount <= authorized_amount)
);
CREATE INDEX ix_fuel_order_vehicle ON fuel_order(vehicle_id, status);
CREATE TRIGGER trg_fuel_order_demo BEFORE INSERT OR UPDATE ON fuel_order
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

-- Transitions autorisées (RG-02).
CREATE FUNCTION nf_fuel_order_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = NEW.status THEN RETURN NEW; END IF;
    IF NOT (
        (OLD.status = 'DRAFT'              AND NEW.status IN ('SUBMITTED','CANCELLED')) OR
        (OLD.status = 'SUBMITTED'          AND NEW.status IN ('APPROVED','CANCELLED','DRAFT')) OR
        (OLD.status = 'APPROVED'           AND NEW.status IN ('PARTIALLY_CONSUMED','SETTLED','CANCELLED','EXPIRED')) OR
        (OLD.status = 'PARTIALLY_CONSUMED' AND NEW.status IN ('SETTLED','EXPIRED'))
    ) THEN
        RAISE EXCEPTION 'Transition de bon interdite : % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'APPROVED' AND NEW.approved_by IS NULL THEN
        RAISE EXCEPTION 'Un bon approuvé doit porter son valideur' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_fuel_order_transition BEFORE UPDATE OF status ON fuel_order
    FOR EACH ROW EXECUTE FUNCTION nf_fuel_order_transition();

-- NF-41 : ravitaillement ; reçu unique par fournisseur (CAR-02).
CREATE TABLE fuel_fill (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fuel_order_id       uuid REFERENCES fuel_order(id),          -- NULL = exception autorisée, tracée
    exception_reason    text,
    vehicle_id          uuid NOT NULL REFERENCES vehicle(id),
    driver_id           uuid REFERENCES driver(id),
    supplier_id         uuid NOT NULL REFERENCES supplier(id),
    filled_at           timestamptz NOT NULL,
    odometer_km         numeric(10,1) CHECK (odometer_km >= 0),
    liters              numeric(9,2)  NOT NULL CHECK (liters > 0),
    unit_price          numeric(12,2) NOT NULL CHECK (unit_price > 0),
    amount              numeric(16,2) NOT NULL CHECK (amount > 0),
    full_tank           boolean NOT NULL,
    receipt_reference   text NOT NULL,
    receipt_file_id     uuid,
    entered_by          uuid NOT NULL REFERENCES app_user(id),
    client_request_id   text,                                      -- idempotence
    is_demo             boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_or_exception CHECK (fuel_order_id IS NOT NULL OR length(btrim(coalesce(exception_reason,''))) > 0),
    CONSTRAINT ck_amount_consistency CHECK (abs(amount - round(liters * unit_price, 2)) <= 1.00)
);
CREATE UNIQUE INDEX ux_fuel_fill_receipt ON fuel_fill(supplier_id, upper(btrim(receipt_reference)));
CREATE UNIQUE INDEX ux_fuel_fill_idem ON fuel_fill(entered_by, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX ix_fuel_fill_vehicle_time ON fuel_fill(vehicle_id, filled_at);
CREATE TRIGGER trg_fuel_fill_demo BEFORE INSERT OR UPDATE ON fuel_fill
    FOR EACH ROW EXECUTE FUNCTION nf_assert_demo_allowed();

-- CAR-01 : consommation atomique du solde. Le verrou de ligne sérialise les saisies concurrentes :
-- deux pleins de 40 L et 30 L sur un bon de 60 L => le second est refusé.
CREATE FUNCTION nf_consume_fuel_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o fuel_order%ROWTYPE;
BEGIN
    IF NEW.fuel_order_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO o FROM fuel_order WHERE id = NEW.fuel_order_id FOR UPDATE;
    IF o.status NOT IN ('APPROVED','PARTIALLY_CONSUMED') THEN
        RAISE EXCEPTION 'CAR-01 : bon % non utilisable (statut %)', o.order_number, o.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.filled_at::date > o.valid_until THEN
        RAISE EXCEPTION 'CAR-01 : bon % expiré le %', o.order_number, o.valid_until
            USING ERRCODE = 'check_violation';
    END IF;
    IF o.vehicle_id <> NEW.vehicle_id THEN
        RAISE EXCEPTION 'CAR-01 : bon % émis pour un autre véhicule', o.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF o.delivered_liters + NEW.liters > o.authorized_liters
       OR o.delivered_amount + NEW.amount > o.authorized_amount THEN
        RAISE EXCEPTION 'CAR-01 : solde du bon % dépassé (autorisé % L / % XAF, déjà délivré % L / % XAF, demandé % L / % XAF)',
            o.order_number, o.authorized_liters, o.authorized_amount,
            o.delivered_liters, o.delivered_amount, NEW.liters, NEW.amount
            USING ERRCODE = 'check_violation';
    END IF;
    UPDATE fuel_order
       SET delivered_liters = delivered_liters + NEW.liters,
           delivered_amount = delivered_amount + NEW.amount,
           status = CASE WHEN delivered_liters + NEW.liters = authorized_liters
                           OR delivered_amount + NEW.amount = authorized_amount
                         THEN 'SETTLED' ELSE 'PARTIALLY_CONSUMED' END,
           version = version + 1
     WHERE id = o.id;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_fuel_fill_consume BEFORE INSERT ON fuel_fill
    FOR EACH ROW EXECUTE FUNCTION nf_consume_fuel_order();
-- Un plein enregistré ne se modifie pas ni ne se supprime : correction = écriture compensatoire tracée (RG-01).
CREATE TRIGGER trg_fuel_fill_immutable BEFORE UPDATE OR DELETE ON fuel_fill
    FOR EACH ROW EXECUTE FUNCTION nf_forbid_modification();

-- NF-45 : factures et lignes, plusieurs lignes par facture, plusieurs pleins par bon.
CREATE TABLE invoice (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id     uuid NOT NULL REFERENCES supplier(id),
    invoice_number  text NOT NULL,
    issued_on       date NOT NULL,
    total_amount    numeric(16,2) NOT NULL CHECK (total_amount >= 0),
    file_id         uuid,
    is_demo         boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, invoice_number)
);

CREATE TABLE invoice_line (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      uuid NOT NULL REFERENCES invoice(id),
    line_no         integer NOT NULL CHECK (line_no > 0),
    fuel_order_id   uuid REFERENCES fuel_order(id),
    fuel_fill_id    uuid REFERENCES fuel_fill(id),
    receipt_reference text,
    liters          numeric(9,2) CHECK (liters >= 0),
    unit_price      numeric(12,2) CHECK (unit_price >= 0),
    tax_amount      numeric(16,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    amount          numeric(16,2) NOT NULL CHECK (amount >= 0),
    UNIQUE (invoice_id, line_no)
);

-- Vue de rapprochement ligne à ligne (CAR-07) : écart facturé vs délivré, sans écriture externe.
CREATE VIEW v_invoice_reconciliation AS
SELECT il.id                 AS invoice_line_id,
       i.invoice_number,
       i.supplier_id,
       il.fuel_fill_id,
       f.receipt_reference,
       il.liters             AS invoiced_liters,
       f.liters              AS delivered_liters,
       il.liters - f.liters  AS liters_gap,
       il.amount             AS invoiced_amount,
       f.amount              AS delivered_amount,
       il.amount - f.amount  AS amount_gap,
       CASE WHEN f.id IS NULL THEN 'LIGNE_SANS_PLEIN'
            WHEN il.liters <> f.liters OR il.amount <> f.amount THEN 'ECART'
            ELSE 'CONCORDANT' END AS reconciliation_state
  FROM invoice_line il
  JOIN invoice i ON i.id = il.invoice_id
  LEFT JOIN fuel_fill f ON f.id = il.fuel_fill_id;
