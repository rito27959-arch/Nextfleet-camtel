-- Tests d'intégrité de la base NexFleet (exécutés par run_db_tests.sh sur une base vierge migrée).
-- Chaque test lève une exception en cas d'échec ; ON_ERROR_STOP arrête la campagne.
\set ON_ERROR_STOP 1
SET client_min_messages = warning;

\o /dev/null
CREATE TEMP TABLE test_result(name text, ok boolean);

CREATE FUNCTION pg_temp.expect_error(test_name text, stmt text, expected_fragment text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        IF position(lower(expected_fragment) IN lower(SQLERRM)) > 0 THEN
            INSERT INTO test_result VALUES (test_name, true);
            RETURN;
        END IF;
        RAISE EXCEPTION 'ÉCHEC % : erreur inattendue « % »', test_name, SQLERRM;
    END;
    RAISE EXCEPTION 'ÉCHEC % : aucune erreur levée', test_name;
END $$;

CREATE FUNCTION pg_temp.expect_true(test_name text, cond boolean) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF cond IS DISTINCT FROM true THEN RAISE EXCEPTION 'ÉCHEC %', test_name; END IF;
    INSERT INTO test_result VALUES (test_name, true);
END $$;

-- ------------------------------------------------------------------ jeu de données FICTIF
INSERT INTO app_environment(kind) VALUES ('TEST');
INSERT INTO organisation_unit(id, code, name, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000a1','BUT-YDE','BUT Yaoundé (fictif)', true),
 ('00000000-0000-0000-0000-0000000000a2','BUT-DLA','BUT Douala (fictif)', true);
INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES
 ('00000000-0000-0000-0000-000000000001','gestionnaire','Gestionnaire fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000002','controleur','Contrôleur fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000003','conducteur','Conducteur fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000004','agent.dla','Agent Douala fictif','00000000-0000-0000-0000-0000000000a2','x',true);
INSERT INTO vehicle(id, registration, make, model, model_year, energy, tank_capacity_l, tank_capacity_verified, org_unit_id, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000b1','CE 4401 LT','Toyota','Hilux',2022,'DIESEL',80,true,'00000000-0000-0000-0000-0000000000a1',true);
INSERT INTO driver(id, user_id, staff_number, full_name, org_unit_id, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-000000000003','MAT-0001','Conducteur fictif','00000000-0000-0000-0000-0000000000a1',true);
INSERT INTO supplier(id, code, name, kind, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000d1','ST-001','Station fictive','STATION',true);

-- ------------------------------------------------------------------ NF-10 unicité immatriculation
SELECT pg_temp.expect_error('NF-10 doublon immatriculation normalisée',
 $$INSERT INTO vehicle(registration, make, model, org_unit_id, is_demo)
   VALUES ('ce-4401-lt','Toyota','Hilux','00000000-0000-0000-0000-0000000000a1',true)$$, 'ux_vehicle_registration');
SELECT pg_temp.expect_error('RG-04 suppression physique interdite',
 $$DELETE FROM vehicle WHERE id='00000000-0000-0000-0000-0000000000b1'$$, 'ajout seul');

-- ------------------------------------------------------------------ NF-04 démo interdite en production
UPDATE app_environment SET kind = 'PRODUCTION';
SELECT pg_temp.expect_error('NF-04 démo refusée en production',
 $$INSERT INTO supplier(code, name, kind, is_demo) VALUES ('ST-X','X','STATION',true)$$, 'NF-04');
UPDATE app_environment SET kind = 'TEST';

-- ------------------------------------------------------------------ NF-02 / NF-50 sessions
INSERT INTO auth_session(user_id, token_sha256, client_kind, expires_at)
 VALUES ('00000000-0000-0000-0000-000000000004', digest('tok-dla','sha256'), 'DESKTOP', now() + interval '8 hours');
UPDATE app_user SET status = 'SUSPENDED' WHERE id = '00000000-0000-0000-0000-000000000004';
SELECT pg_temp.expect_true('REC-08 suspension révoque les sessions',
 (SELECT revoked_at IS NOT NULL FROM auth_session WHERE token_sha256 = digest('tok-dla','sha256')));
SELECT pg_temp.expect_error('NF-02 auto-attribution de rôle interdite',
 $$INSERT INTO user_role_grant(user_id, role_code, scope_org_id, granted_by)
   VALUES ('00000000-0000-0000-0000-000000000001','ADMIN','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001')$$,
 'ck_no_self_grant');
SELECT pg_temp.expect_error('Délégation sans date de fin refusée',
 $$INSERT INTO user_role_grant(user_id, role_code, scope_org_id, is_delegation, granted_by)
   VALUES ('00000000-0000-0000-0000-000000000003','CONTROLLER','00000000-0000-0000-0000-0000000000a1',true,'00000000-0000-0000-0000-000000000001')$$,
 'ck_delegation_has_end');

-- ------------------------------------------------------------------ affectations sans chevauchement
INSERT INTO vehicle_assignment(vehicle_id, driver_id, org_unit_id, period) VALUES
 ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000a1',
  tstzrange('2026-01-01','2026-06-01'));
SELECT pg_temp.expect_error('NF-12 chevauchement d''affectation refusé',
 $$INSERT INTO vehicle_assignment(vehicle_id, org_unit_id, period) VALUES
   ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000a1', tstzrange('2026-05-01','2026-07-01'))$$,
 'ex_vehicle_assignment_overlap');

-- ------------------------------------------------------------------ NF-40 / CAR-01 bons carburant
SELECT pg_temp.expect_error('NF-40 demandeur ne valide pas son bon',
 $$INSERT INTO fuel_order(order_number, vehicle_id, supplier_id, org_unit_id, requested_by, approved_by, approved_at,
     authorized_liters, authorized_amount, valid_until, status)
   VALUES ('BON-SELF','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000003', now(), 60, 50000, '2026-12-31','DRAFT')$$,
 'ck_no_self_approval');

INSERT INTO fuel_order(id, order_number, vehicle_id, supplier_id, org_unit_id, requested_by,
                       authorized_liters, authorized_amount, valid_until, is_demo)
VALUES ('00000000-0000-0000-0000-0000000000e1','BON-0001','00000000-0000-0000-0000-0000000000b1',
        '00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000a1',
        '00000000-0000-0000-0000-000000000003', 60, 50000, '2026-12-31', true);

SELECT pg_temp.expect_error('RG-02 transition brouillon -> approuvé interdite',
 $$UPDATE fuel_order SET status='APPROVED', approved_by='00000000-0000-0000-0000-000000000002', approved_at=now()
   WHERE id='00000000-0000-0000-0000-0000000000e1'$$, 'Transition de bon interdite');
SELECT pg_temp.expect_error('CAR-01 plein sur bon non approuvé refusé',
 $$INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
   VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
   '2026-10-01 08:00+01', 10, 800, 8000, false, 'R-000', '00000000-0000-0000-0000-000000000003')$$, 'non utilisable');

UPDATE fuel_order SET status='SUBMITTED' WHERE id='00000000-0000-0000-0000-0000000000e1';
UPDATE fuel_order SET status='APPROVED', approved_by='00000000-0000-0000-0000-000000000002', approved_at=now()
 WHERE id='00000000-0000-0000-0000-0000000000e1';

INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, odometer_km, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
        '2026-10-01 08:00+01', 10000, 40, 800, 32000, true, 'R-001', '00000000-0000-0000-0000-000000000003');
SELECT pg_temp.expect_true('CAR-01 solde partiellement consommé',
 (SELECT status = 'PARTIALLY_CONSUMED' AND delivered_liters = 40 FROM fuel_order WHERE id='00000000-0000-0000-0000-0000000000e1'));
SELECT pg_temp.expect_error('CAR-01 bon de 60 L : 40 L puis 30 L -> second refusé',
 $$INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
   VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
   '2026-10-02 08:00+01', 30, 800, 24000, false, 'R-002', '00000000-0000-0000-0000-000000000003')$$, 'solde du bon');
SELECT pg_temp.expect_error('CAR-02 reçu répété bloqué (casse/espaces ignorés)',
 $$INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
   VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
   '2026-10-02 08:00+01', 5, 800, 4000, false, ' r-001 ', '00000000-0000-0000-0000-000000000003')$$, 'ux_fuel_fill_receipt');
SELECT pg_temp.expect_error('Montant incohérent avec litres × prix refusé',
 $$INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
   VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
   '2026-10-02 08:00+01', 5, 800, 9000, false, 'R-003', '00000000-0000-0000-0000-000000000003')$$, 'ck_amount_consistency');
SELECT pg_temp.expect_error('RG-01 plein immuable',
 $$UPDATE fuel_fill SET liters = 1$$, 'ajout seul');
INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
        '2026-10-03 08:00+01', 20, 800, 16000, false, 'R-004', '00000000-0000-0000-0000-000000000003');
SELECT pg_temp.expect_true('CAR-01 bon soldé à 60 L',
 (SELECT status = 'SETTLED' AND delivered_liters = 60 FROM fuel_order WHERE id='00000000-0000-0000-0000-0000000000e1'));

-- ------------------------------------------------------------------ CAR-07 facture 55 L vs reçu 50 L
INSERT INTO supplier(id, code, name, kind, is_demo) VALUES ('00000000-0000-0000-0000-0000000000d2','ST-002','Station 2 fictive','STATION',true);
INSERT INTO fuel_fill(id, fuel_order_id, exception_reason, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
VALUES ('00000000-0000-0000-0000-0000000000f1', NULL, 'Exception atelier autorisée (fictif)',
        '00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d2','2026-10-04 08:00+01', 50, 800, 40000, true, 'R-100',
        '00000000-0000-0000-0000-000000000003');
INSERT INTO invoice(id, supplier_id, invoice_number, issued_on, total_amount, is_demo)
VALUES ('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-0000000000d2','FAC-1','2026-10-31',44000,true);
INSERT INTO invoice_line(invoice_id, line_no, fuel_fill_id, liters, unit_price, amount)
VALUES ('00000000-0000-0000-0000-000000000101',1,'00000000-0000-0000-0000-0000000000f1',55,800,44000);
SELECT pg_temp.expect_true('CAR-07 écart de 5 L et 4000 XAF détecté',
 (SELECT liters_gap = 5 AND amount_gap = 4000 AND reconciliation_state = 'ECART' FROM v_invoice_reconciliation));
SELECT pg_temp.expect_error('Plein sans bon ni motif d''exception refusé',
 $$INSERT INTO fuel_fill(vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
   VALUES ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d2','2026-10-05 08:00+01', 10, 800, 8000, false, 'R-101',
   '00000000-0000-0000-0000-000000000003')$$, 'ck_order_or_exception');

-- ------------------------------------------------------------------ REC-GPS-01 collecte limitée à la mission
INSERT INTO mission(id, reference, org_unit_id, vehicle_id, driver_id, purpose, planned_start, planned_end, created_by, is_demo)
VALUES ('00000000-0000-0000-0000-000000000201','MIS-001','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1',
        '00000000-0000-0000-0000-0000000000c1','Livraison fictive', now() - interval '3 hours', now() + interval '3 hours',
        '00000000-0000-0000-0000-000000000001', true);
SELECT pg_temp.expect_error('REC-GPS-01 point refusé avant démarrage',
 $$INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
   VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-0', now(),
   ST_GeogFromText('POINT(11.5021 3.8480)'), 8, 'fused')$$, 'non démarrée');

UPDATE mission SET status='AUTHORIZED', authorized_by='00000000-0000-0000-0000-000000000002', authorized_at=now() - interval '2 hours'
 WHERE id='00000000-0000-0000-0000-000000000201';
UPDATE mission SET status='ACTIVE', started_at=now() - interval '1 hour' WHERE id='00000000-0000-0000-0000-000000000201';

SELECT pg_temp.expect_error('NF-70 appareil sans autorisation refusé',
 $$INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
   VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-0', now() - interval '30 minutes',
   ST_GeogFromText('POINT(11.5021 3.8480)'), 8, 'fused')$$, 'non autorisé');

INSERT INTO tracking_authorization(mission_id, source_device_id, informed_at, notice_version, granted_at)
VALUES ('00000000-0000-0000-0000-000000000201','DEV-1', now() - interval '61 minutes', 'NOTICE-GPS-v1', now() - interval '1 hour');

INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-1', now() - interval '30 minutes',
        ST_GeogFromText('POINT(11.5021 3.8480)'), 8, 'fused'),
       ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-2', now() - interval '20 minutes',
        ST_GeogFromText('POINT(11.5121 3.8580)'), 9, 'fused');
SELECT pg_temp.expect_true('PostGIS distance calculée entre deux points (≈1,57 km)',
 (SELECT round(ST_Distance(a.position, b.position)::numeric) BETWEEN 1500 AND 1650
    FROM location_point a, location_point b WHERE a.client_fix_id='fix-1' AND b.client_fix_id='fix-2'));
SELECT pg_temp.expect_error('NF-74 lot rejoué : pas de doublon',
 $$INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
   VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-1', now() - interval '30 minutes',
   ST_GeogFromText('POINT(11.5021 3.8480)'), 8, 'fused')$$, 'location_point_source_device_id_client_fix_id_key');

UPDATE mission SET status='FINISHED', finished_at=now() - interval '10 minutes' WHERE id='00000000-0000-0000-0000-000000000201';
SELECT pg_temp.expect_true('Clôture de mission révoque l''autorisation de collecte',
 (SELECT revoked_at IS NOT NULL FROM tracking_authorization WHERE source_device_id='DEV-1'));
-- Point tamponné hors réseau, relevé AVANT la clôture : accepté à la reconnexion.
INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-3', now() - interval '15 minutes',
        ST_GeogFromText('POINT(11.5150 3.8600)'), 12, 'fused');
SELECT pg_temp.expect_error('REC-GPS-01 aucun point après la fin de mission',
 $$INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, provider)
   VALUES ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000b1','DEV-1','fix-4', now() - interval '1 minute',
   ST_GeogFromText('POINT(11.52 3.87)'), 8, 'fused')$$, 'hors période');

-- ------------------------------------------------------------------ REC-CHAT-02 et REC-SEC-01
INSERT INTO conversation(id, kind, org_unit_id, mission_id, title)
VALUES ('00000000-0000-0000-0000-000000000301','MISSION','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000201','MIS-001');
INSERT INTO conversation_participant(conversation_id, user_id) VALUES
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000003');
INSERT INTO message(conversation_id, sender_id, client_message_id, body) VALUES
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001','c-1','Merci d''envoyer le reçu du plein.'),
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000003','c-1','Reçu envoyé.');
SELECT pg_temp.expect_true('NF-61 séquence serveur stable 1,2',
 (SELECT array_agg(server_seq ORDER BY server_seq) = ARRAY[1,2]::bigint[] FROM message));
SELECT pg_temp.expect_error('REC-CHAT-02 réémission du même message refusée',
 $$INSERT INTO message(conversation_id, sender_id, client_message_id, body)
   VALUES ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000003','c-1','Reçu envoyé.')$$,
 'message_sender_id_client_message_id_key');
SELECT pg_temp.expect_error('REC-SEC-01 non-participant d''une autre entité ne peut écrire',
 $$INSERT INTO message(conversation_id, sender_id, client_message_id, body)
   VALUES ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000004','x-1','intrusion')$$,
 'pas participant');

-- ------------------------------------------------------------------ anomalies : pas de verdict de fraude
INSERT INTO anomaly_rule(rule_id, rule_version, label) VALUES ('CAR-07',1,'Écart facture / plein');
SELECT pg_temp.expect_error('NF-44 statut « FRAUDE » impossible',
 $$INSERT INTO anomaly(rule_id, rule_version, org_unit_id, severity, data_quality, summary, evidence, status, dedup_key)
   VALUES ('CAR-07',1,'00000000-0000-0000-0000-0000000000a1','MEDIUM','FIABLE','x','{}','FRAUDE','k1')$$, 'anomaly_status_check');

-- ------------------------------------------------------------------ 3D : licence obligatoire
INSERT INTO asset_license(id, license_id, source_url, author, attribution_text, redistribution_allowed, commercial_use_allowed)
VALUES ('00000000-0000-0000-0000-000000000401','INCONNUE','https://exemple.invalid/modele','Auteur inconnu','—', false, false);
SELECT pg_temp.expect_error('3D-01 publication refusée sans licence redistribuable revue',
 $$INSERT INTO vehicle_3d_asset(license_id, match_level, format, storage_key, sha256, size_bytes, status)
   VALUES ('00000000-0000-0000-0000-000000000401','GENERIC','GLB','k','h',1,'PUBLISHED')$$, 'publication refusée');
SELECT pg_temp.expect_error('3D-02 un dérivé de photo exige son original',
 $$INSERT INTO vehicle_media(vehicle_id, kind, storage_key, sha256, mime_type, size_bytes, uploaded_by)
   VALUES ('00000000-0000-0000-0000-0000000000b1','PHOTO_DETOUREE','k','h','image/png',100,'00000000-0000-0000-0000-000000000001')$$,
 'vehicle_media_check');

-- ------------------------------------------------------------------ SEC-08 journal chaîné et immuable
INSERT INTO audit_event(actor_id, action, object_type, object_id, reason, old_value, new_value)
VALUES ('00000000-0000-0000-0000-000000000001','UPDATE','vehicle','b1','Correction kilométrage','{"km":100}','{"km":110}');
INSERT INTO audit_event(actor_id, action, object_type, object_id) VALUES ('00000000-0000-0000-0000-000000000002','EXPORT','audit','*');
SELECT pg_temp.expect_true('SEC-08 chaînage des empreintes',
 (SELECT b.prev_hash = a.row_hash FROM audit_event a, audit_event b WHERE b.id = a.id + 1 ORDER BY a.id LIMIT 1));
SELECT pg_temp.expect_error('SEC-08 modification du journal interdite',
 $$UPDATE audit_event SET reason = 'falsifié'$$, 'ajout seul');


-- ------------------------------------------------------------------ V4 : parité ancien NexFleet
SELECT pg_temp.expect_true('NF-13 référentiel des 12 points de contrôle', (SELECT count(*) = 12 FROM inspection_point));
INSERT INTO inspection(id, vehicle_id, kind, inspected_at, result, author_id)
VALUES ('00000000-0000-0000-0000-000000000501','00000000-0000-0000-0000-0000000000b1','PERIODIQUE', now(), 'RESERVES',
        '00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_error('NF-13 un défaut doit être décrit',
 $$INSERT INTO inspection_item(inspection_id, point_code, state) VALUES ('00000000-0000-0000-0000-000000000501','FREINS','DEFAUT')$$,
 'inspection_item_check');
INSERT INTO inspection_item(inspection_id, point_code, state, critical, comment)
VALUES ('00000000-0000-0000-0000-000000000501','FREINS','DEFAUT', true, 'Plaquettes avant usées');
SELECT pg_temp.expect_error('NF-14 clôture sans justificatif ni visa refusée',
 $$INSERT INTO maintenance_order(vehicle_id, category_code, title, status, cost_xaf, odometer_km, work_done, requested_by)
   VALUES ('00000000-0000-0000-0000-0000000000b1','FREINAGE','Plaquettes','CLOTUREE', 85000, 42000, 'Remplacement', '00000000-0000-0000-0000-000000000001')$$,
 'ck_closure_complete');
INSERT INTO maintenance_order(id, vehicle_id, category_code, title, requested_by)
VALUES ('00000000-0000-0000-0000-000000000601','00000000-0000-0000-0000-0000000000b1','VIDANGE','Vidange 45 000 km','00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_error('NF-15 vidange liée à un ordre : coût non compté deux fois',
 $$INSERT INTO oil_change(vehicle_id, maintenance_order_id, done_on, odometer_km, interval_km, cost_xaf, author_id)
   VALUES ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-000000000601','2026-10-01', 45000, 10000, 60000,
   '00000000-0000-0000-0000-000000000001')$$, 'ck_no_double_cost');
INSERT INTO oil_change(vehicle_id, done_on, odometer_km, interval_km, oil_filter, cost_xaf, author_id)
VALUES ('00000000-0000-0000-0000-0000000000b1','2026-10-01', 45000, 10000, true, 60000, '00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_true('NF-15 prochaine vidange calculée à 55 000 km',
 (SELECT next_due_km = 55000 FROM oil_change WHERE vehicle_id = '00000000-0000-0000-0000-0000000000b1'));
INSERT INTO tire(id, size, brand) VALUES ('00000000-0000-0000-0000-000000000701','265/65 R17','Marque fictive');
INSERT INTO tire_event(tire_id, vehicle_id, kind, position, condition, occurred_on, author_id) VALUES
 ('00000000-0000-0000-0000-000000000701','00000000-0000-0000-0000-0000000000b1','MONTAGE','AVG','NEUF','2026-01-10','00000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000701','00000000-0000-0000-0000-0000000000b1','PERMUTATION','ARD','BON','2026-07-02','00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_true('NF-16 permutation : position courante ARD, origine AVG conservée',
 (SELECT position = 'ARD' FROM v_tire_current_position) AND (SELECT count(*) = 2 FROM tire_event));
SELECT pg_temp.expect_error('NF-16 historique de pneus non modifiable', $$UPDATE tire_event SET position = 'AVD'$$, 'ajout seul');
INSERT INTO vehicle_document(vehicle_id, doc_type, valid_until) VALUES
 ('00000000-0000-0000-0000-0000000000b1','ASSURANCE','2026-10-30'),
 ('00000000-0000-0000-0000-0000000000b1','ASSURANCE','2025-10-30'),
 ('00000000-0000-0000-0000-0000000000b1','VIGNETTE','2026-10-15');
UPDATE driver SET id_card_expires_on = '2027-06-01' WHERE id = '00000000-0000-0000-0000-0000000000c1';
INSERT INTO driver_license(driver_id, license_number, categories, expires_on)
VALUES ('00000000-0000-0000-0000-0000000000c1','PERMIS-FICTIF-1', ARRAY['B','C'], '2026-11-05');
SELECT pg_temp.expect_true('NF-11 échéances unifiées : seule la dernière assurance compte ; niveaux cohérents',
 (SELECT count(*) = 1 FROM nf_due_dates('2026-10-20', 30) WHERE due_kind = 'ASSURANCE')
 AND (SELECT level = 'A_ECHEANCE' AND days_left = 10 FROM nf_due_dates('2026-10-20', 30) WHERE due_kind = 'ASSURANCE')
 AND (SELECT level = 'EXPIRE' FROM nf_due_dates('2026-10-20', 30) WHERE due_kind = 'VIGNETTE')
 AND (SELECT level = 'A_ECHEANCE' FROM nf_due_dates('2026-10-20', 30) WHERE due_kind = 'PERMIS')
 AND (SELECT level = 'A_JOUR' FROM nf_due_dates('2026-10-20', 30) WHERE due_kind = 'CNI'));
SELECT pg_temp.expect_error('NF-19 position déclarée sans horodatage refusée',
 $$UPDATE vehicle SET declared_lat = 3.85, declared_lng = 11.50 WHERE id = '00000000-0000-0000-0000-0000000000b1'$$, 'ck_declared_position');
INSERT INTO budget_plan_line(fiscal_year, org_unit_id, category_code, task, q1_amount, q3_amount, source_file, source_row, import_batch_id)
VALUES (2026, '00000000-0000-0000-0000-0000000000a1', 'FREINAGE', 'Plaquettes (fictif)', 150000, 150000, 'plan_2026.xlsx', 12, gen_random_uuid());
SELECT pg_temp.expect_true('NF-21 montant annuel = somme des trimestres (300 000 XAF)',
 (SELECT annual_amount = 300000 FROM budget_plan_line));
SELECT pg_temp.expect_error('NF-26 réimport de la même ligne sans doublon',
 $$INSERT INTO budget_plan_line(fiscal_year, org_unit_id, category_code, task, source_file, source_row, import_batch_id)
   VALUES (2026, '00000000-0000-0000-0000-0000000000a1', 'FREINAGE', 'x', 'plan_2026.xlsx', 12, gen_random_uuid())$$,
 'budget_plan_line_fiscal_year_org_unit_id_source_file_source_key');

-- ------------------------------------------------------------------ bilan
\o
SELECT count(*) AS tests_reussis FROM test_result;
SELECT name AS test FROM test_result ORDER BY name;
