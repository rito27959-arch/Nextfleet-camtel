-- Jeu de DÉMONSTRATION (environnement DEMO uniquement : la base refuse is_demo=true ailleurs).
-- Personnes, véhicules et missions FICTIFS. Mot de passe commun fourni par NF_DEMO_PASSWORD (haché bcrypt : {{PWD}}).
INSERT INTO organisation_unit(id, code, name, parent_id, is_demo) VALUES
 ('d0000000-0000-0000-0000-0000000000a0','DEMO-CAMTEL','CAMTEL (démo)', NULL, true),
 ('d0000000-0000-0000-0000-0000000000a1','DEMO-YDE','BUT Yaoundé (démo)','d0000000-0000-0000-0000-0000000000a0', true),
 ('d0000000-0000-0000-0000-0000000000a2','DEMO-DLA','BUT Douala (démo)','d0000000-0000-0000-0000-0000000000a0', true);

INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES
 ('d0000000-0000-0000-0000-000000000001','gestionnaire','Aline Mvondo (démo)','d0000000-0000-0000-0000-0000000000a1','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000002','controleur','Serge Fotso (démo)','d0000000-0000-0000-0000-0000000000a0','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000003','conducteur','Félix Kamga (démo)','d0000000-0000-0000-0000-0000000000a1','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000004','mbarga','Alain Mbarga (démo)','d0000000-0000-0000-0000-0000000000a1','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000005','abena','Lucie Abena (démo)','d0000000-0000-0000-0000-0000000000a2','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000006','maintenance','Paul Essomba (démo)','d0000000-0000-0000-0000-0000000000a1','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000007','auditeur','Mireille Ondoa (démo)','d0000000-0000-0000-0000-0000000000a0','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000008','chef.parc','Jean Nguema (démo)','d0000000-0000-0000-0000-0000000000a1','{{PWD}}',true),
 ('d0000000-0000-0000-0000-000000000009','admin','Administration technique (démo)','d0000000-0000-0000-0000-0000000000a0','{{PWD}}',true);
INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES
 ('d0000000-0000-0000-0000-000000000001','FLEET_MANAGER','d0000000-0000-0000-0000-0000000000a0'),
 ('d0000000-0000-0000-0000-000000000002','CONTROLLER','d0000000-0000-0000-0000-0000000000a0'),
 ('d0000000-0000-0000-0000-000000000003','DRIVER','d0000000-0000-0000-0000-0000000000a1'),
 ('d0000000-0000-0000-0000-000000000004','DRIVER','d0000000-0000-0000-0000-0000000000a1'),
 ('d0000000-0000-0000-0000-000000000005','DRIVER','d0000000-0000-0000-0000-0000000000a2'),
 ('d0000000-0000-0000-0000-000000000006','MAINTENANCE','d0000000-0000-0000-0000-0000000000a0'),
 ('d0000000-0000-0000-0000-000000000007','AUDITOR','d0000000-0000-0000-0000-0000000000a0'),
 ('d0000000-0000-0000-0000-000000000008','FLEET_MANAGER','d0000000-0000-0000-0000-0000000000a0'),
 ('d0000000-0000-0000-0000-000000000009','ADMIN','d0000000-0000-0000-0000-0000000000a0');

INSERT INTO vehicle(id, registration, make, model, model_year, energy, tank_capacity_l, tank_capacity_verified, org_unit_id, status, category, is_demo) VALUES
 ('d0000000-0000-0000-0000-0000000000b1','LT 5520 CE','Renault','Duster',2021,'DIESEL',50,true,'d0000000-0000-0000-0000-0000000000a1','ON_MISSION','SUV',true),
 ('d0000000-0000-0000-0000-0000000000b2','CE 4210 MK','Toyota','Hilux',2022,'DIESEL',80,true,'d0000000-0000-0000-0000-0000000000a2','ON_MISSION','Pick-up',true),
 ('d0000000-0000-0000-0000-0000000000b3','CE 7302 RT','Isuzu','D-Max',2020,'DIESEL',76,true,'d0000000-0000-0000-0000-0000000000a1','AVAILABLE','Pick-up',true),
 ('d0000000-0000-0000-0000-0000000000b4','CE 4401 LT','Toyota','Land Cruiser',2019,'DIESEL',130,true,'d0000000-0000-0000-0000-0000000000a1','AVAILABLE','4x4',true),
 ('d0000000-0000-0000-0000-0000000000b5','LT 1180 PL','Toyota','Hiace',2018,'DIESEL',70,true,'d0000000-0000-0000-0000-0000000000a2','MAINTENANCE','Minibus',true),
 ('d0000000-0000-0000-0000-0000000000b6','CE 9031 AB','Peugeot','301',2021,'ESSENCE',50,true,'d0000000-0000-0000-0000-0000000000a1','AVAILABLE','Berline',true);
INSERT INTO driver(id, user_id, staff_number, full_name, org_unit_id, phone, is_demo) VALUES
 ('d0000000-0000-0000-0000-0000000000c1','d0000000-0000-0000-0000-000000000003','DEMO-0003','Félix Kamga','d0000000-0000-0000-0000-0000000000a1','+237 6 00 00 00 03',true),
 ('d0000000-0000-0000-0000-0000000000c2','d0000000-0000-0000-0000-000000000004','DEMO-0004','Alain Mbarga','d0000000-0000-0000-0000-0000000000a1','+237 6 00 00 00 04',true),
 ('d0000000-0000-0000-0000-0000000000c3','d0000000-0000-0000-0000-000000000005','DEMO-0005','Lucie Abena','d0000000-0000-0000-0000-0000000000a2','+237 6 00 00 00 05',true);

-- Missions : deux EN COURS alimentées par le simulateur de démonstration (émetteurs SIM-DEMO-*), une AUTORISÉE que le
-- conducteur de démonstration peut démarrer depuis un vrai téléphone, une TERMINÉE hier avec sa trace.
INSERT INTO mission(id, reference, org_unit_id, vehicle_id, driver_id, purpose, origin_label, destination_label,
                    planned_start, planned_end, created_by, is_demo) VALUES
 ('d0000000-0000-0000-0000-000000000214','MIS-2026-014','d0000000-0000-0000-0000-0000000000a1','d0000000-0000-0000-0000-0000000000b1',
  'd0000000-0000-0000-0000-0000000000c2','Intervention fibre — site de Mbalmayo','Yaoundé, Poste centrale','Mbalmayo',
  now() - interval '30 minutes', now() + interval '30 days','d0000000-0000-0000-0000-000000000001',true),
 ('d0000000-0000-0000-0000-000000000213','MIS-2026-013','d0000000-0000-0000-0000-0000000000a2','d0000000-0000-0000-0000-0000000000b2',
  'd0000000-0000-0000-0000-0000000000c3','Maintenance station côtière','Kribi, agence','Campo (direction)',
  now() - interval '3 hours', now() + interval '30 days','d0000000-0000-0000-0000-000000000001',true),
 ('d0000000-0000-0000-0000-000000000215','MIS-2026-015','d0000000-0000-0000-0000-0000000000a1','d0000000-0000-0000-0000-0000000000b3',
  'd0000000-0000-0000-0000-0000000000c1','Relevé de compteurs — antenne d''Obala','Yaoundé, Poste centrale','Obala',
  now() - interval '10 minutes', now() + interval '30 days','d0000000-0000-0000-0000-000000000001',true),
 ('d0000000-0000-0000-0000-000000000212','MIS-2026-012','d0000000-0000-0000-0000-0000000000a1','d0000000-0000-0000-0000-0000000000b4',
  'd0000000-0000-0000-0000-0000000000c1','Livraison de matériel — Soa','Yaoundé, Poste centrale','Soa',
  now() - interval '1 day 2 hours', now() - interval '1 day','d0000000-0000-0000-0000-000000000001',true);
UPDATE mission SET status = 'AUTHORIZED', authorized_by = 'd0000000-0000-0000-0000-000000000008', authorized_at = planned_start
 WHERE id IN ('d0000000-0000-0000-0000-000000000214','d0000000-0000-0000-0000-000000000213','d0000000-0000-0000-0000-000000000215','d0000000-0000-0000-0000-000000000212');
UPDATE mission SET status = 'ACTIVE', started_at = planned_start
 WHERE id IN ('d0000000-0000-0000-0000-000000000214','d0000000-0000-0000-0000-000000000213','d0000000-0000-0000-0000-000000000212');

INSERT INTO tracking_source(unique_id, kind, label, registered_by) VALUES
 ('SIM-DEMO-014','ANDROID','Simulateur de démonstration (MIS-2026-014)','d0000000-0000-0000-0000-000000000009'),
 ('SIM-DEMO-013','ANDROID','Simulateur de démonstration (MIS-2026-013)','d0000000-0000-0000-0000-000000000009'),
 ('SIM-DEMO-012','ANDROID','Simulateur de démonstration (MIS-2026-012)','d0000000-0000-0000-0000-000000000009');
INSERT INTO tracking_authorization(mission_id, source_device_id, informed_at, notice_version, granted_at)
SELECT id, 'SIM-DEMO-' || right(reference, 3), started_at, 'NOTICE-GPS-v1', started_at FROM mission
 WHERE id IN ('d0000000-0000-0000-0000-000000000214','d0000000-0000-0000-0000-000000000213','d0000000-0000-0000-0000-000000000212');

-- Trace d'hier (Yaoundé → Soa, 66 min, un point par minute), puis clôture.
INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, speed_mps, provider)
SELECT 'd0000000-0000-0000-0000-000000000212','d0000000-0000-0000-0000-0000000000b4','SIM-DEMO-012','seed-' || g,
       (now() - interval '1 day 2 hours') + g * interval '1 minute',
       ST_SetSRID(ST_MakePoint(11.5167 + (11.5958 - 11.5167) * g / 66.0 + 0.004 * sin(g / 5.0),
                               3.8667 + (3.9833 - 3.8667) * g / 66.0), 4326)::geography,
       6 + (g % 5), CASE WHEN g BETWEEN 30 AND 36 THEN 0 ELSE 9 + 3 * sin(g / 4.0) END, 'simulation-demo'
  FROM generate_series(0, 66) g;
UPDATE mission SET status = 'FINISHED', finished_at = started_at + interval '66 minutes' WHERE id = 'd0000000-0000-0000-0000-000000000212';

-- Conversations de mission et quelques messages.
INSERT INTO conversation(id, kind, org_unit_id, mission_id, title) VALUES
 ('d0000000-0000-0000-0000-000000000314','MISSION','d0000000-0000-0000-0000-0000000000a1','d0000000-0000-0000-0000-000000000214','MIS-2026-014'),
 ('d0000000-0000-0000-0000-000000000315','MISSION','d0000000-0000-0000-0000-0000000000a1','d0000000-0000-0000-0000-000000000215','MIS-2026-015');
INSERT INTO conversation_participant(conversation_id, user_id) VALUES
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000001'),
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000004'),
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000002'),
 ('d0000000-0000-0000-0000-000000000315','d0000000-0000-0000-0000-000000000001'),
 ('d0000000-0000-0000-0000-000000000315','d0000000-0000-0000-0000-000000000003');
INSERT INTO message(conversation_id, sender_id, client_message_id, body) VALUES
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000001','seed-314-1','Bonjour Alain, mission autorisée. Merci de confirmer le départ.'),
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000004','seed-314-2','Départ confirmé, compteur 61 204 km.'),
 ('d0000000-0000-0000-0000-000000000314','d0000000-0000-0000-0000-000000000002','seed-314-3','Le site de Mbalmayo est prévenu de votre arrivée.'),
 ('d0000000-0000-0000-0000-000000000315','d0000000-0000-0000-0000-000000000001','seed-315-1','Félix, la mission d''Obala est autorisée. Tu peux la démarrer depuis l''application quand tu es prêt.');

-- Documents à échéance (écran Échéances).
INSERT INTO vehicle_document(vehicle_id, doc_type, reference, valid_until) VALUES
 ('d0000000-0000-0000-0000-0000000000b1','ASSURANCE','POL-DEMO-1', current_date + 12),
 ('d0000000-0000-0000-0000-0000000000b2','VIGNETTE','VIG-DEMO-2', current_date - 3),
 ('d0000000-0000-0000-0000-0000000000b3','CONTROLE_TECHNIQUE','CT-DEMO-3', current_date + 95),
 ('d0000000-0000-0000-0000-0000000000b5','ASSURANCE','POL-DEMO-5', current_date + 25);
