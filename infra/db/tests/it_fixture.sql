-- Jeu de données FICTIF des tests d'intégration GPS et messagerie (tools/run-integration-tests.sh).
-- Jetons de session de test : SHA-256 de « tok-<login> » ; ils n'existent que dans la base de test.
INSERT INTO app_environment(kind) VALUES ('TEST');
INSERT INTO organisation_unit(id, code, name, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000a1','BUT-YDE','BUT Yaoundé (fictif)', true),
 ('00000000-0000-0000-0000-0000000000a2','BUT-DLA','BUT Douala (fictif)', true);
INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES
 ('00000000-0000-0000-0000-000000000001','gestionnaire','Gestionnaire fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000002','controleur','Contrôleur fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000003','conducteur','Conducteur fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000004','agent.dla','Agent Douala fictif','00000000-0000-0000-0000-0000000000a2','x',true),
 ('00000000-0000-0000-0000-000000000005','maintenance','Atelier fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000006','admin','Admin technique fictif','00000000-0000-0000-0000-0000000000a1','x',true),
 ('00000000-0000-0000-0000-000000000007','auditeur','Auditeur fictif','00000000-0000-0000-0000-0000000000a1','x',true);
INSERT INTO user_role_grant(user_id, role_code, scope_org_id, granted_by) VALUES
 ('00000000-0000-0000-0000-000000000001','FLEET_MANAGER','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000006'),
 ('00000000-0000-0000-0000-000000000002','CONTROLLER','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000006'),
 ('00000000-0000-0000-0000-000000000003','DRIVER','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000006'),
 ('00000000-0000-0000-0000-000000000004','DRIVER','00000000-0000-0000-0000-0000000000a2','00000000-0000-0000-0000-000000000006'),
 ('00000000-0000-0000-0000-000000000005','MAINTENANCE','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000006'),
 ('00000000-0000-0000-0000-000000000006','ADMIN','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000007','AUDITOR','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000006');
INSERT INTO auth_session(user_id, token_sha256, client_kind, expires_at)
SELECT id, sha256(convert_to('tok-' || login, 'UTF8')), 'DESKTOP', now() + interval '8 hours' FROM app_user;

INSERT INTO vehicle(id, registration, make, model, model_year, energy, tank_capacity_l, tank_capacity_verified, org_unit_id, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000b1','LT 4471 AB','Mitsubishi','L200',2021,'DIESEL',75,true,'00000000-0000-0000-0000-0000000000a1',true),
 ('00000000-0000-0000-0000-0000000000b2','CE 3205 KJ','Toyota','Hilux',2022,'DIESEL',80,true,'00000000-0000-0000-0000-0000000000a1',true);
INSERT INTO driver(id, user_id, staff_number, full_name, org_unit_id, is_demo) VALUES
 ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-000000000003','MAT-0001','Conducteur fictif','00000000-0000-0000-0000-0000000000a1',true);

-- Mission EN COURS depuis une heure, autorisée par le contrôleur ; téléphone AND-7F3K autorisé (notice v1).
INSERT INTO mission(id, reference, org_unit_id, vehicle_id, driver_id, purpose, planned_start, planned_end, created_by, is_demo)
VALUES ('00000000-0000-0000-0000-000000000201','MIS-2026-014','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1',
        '00000000-0000-0000-0000-0000000000c1','Intervention site Mbalmayo (fictif)', now() - interval '2 hours', now() + interval '4 hours',
        '00000000-0000-0000-0000-000000000001', true),
       ('00000000-0000-0000-0000-000000000202','MIS-2026-015','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b2',
        '00000000-0000-0000-0000-0000000000c1','Mission de demain (fictif)', now() + interval '1 day', now() + interval '2 days',
        '00000000-0000-0000-0000-000000000001', true);
UPDATE mission SET status = 'AUTHORIZED', authorized_by = '00000000-0000-0000-0000-000000000002', authorized_at = now() - interval '90 minutes';
UPDATE mission SET status = 'ACTIVE', started_at = now() - interval '1 hour' WHERE id = '00000000-0000-0000-0000-000000000201';
INSERT INTO tracking_source(unique_id, kind, label, registered_by) VALUES
 ('AND-7F3K','ANDROID','Téléphone de mission (fictif)','00000000-0000-0000-0000-000000000006');
INSERT INTO tracking_authorization(mission_id, source_device_id, informed_at, notice_version, granted_at) VALUES
 ('00000000-0000-0000-0000-000000000201','AND-7F3K', now() - interval '61 minutes','NOTICE-GPS-v1', now() - interval '1 hour'),
 ('00000000-0000-0000-0000-000000000202','AND-7F3K', now(),'NOTICE-GPS-v1', now());

-- Conversation de mission : gestionnaire, conducteur, contrôleur ; l'auditeur y est ajouté pour prouver le refus d'écriture.
INSERT INTO conversation(id, kind, org_unit_id, mission_id, title) VALUES
 ('00000000-0000-0000-0000-000000000301','MISSION','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000201','MIS-2026-014');
INSERT INTO conversation_participant(conversation_id, user_id) VALUES
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000003'),
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000002'),
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000007');
