import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('legacy', Path(__file__).parents[1] / 'migrate_legacy.py')
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)

STAMP = '2026-01-01T10:00:00'
ORG = 'a7010000-0000-4000-8000-000000000001'
ACTOR = 'a7010000-0000-4000-8000-000000000002'

def fixture(path, data):
    with sqlite3.connect(path) as db:
        for table,rows in data.items():
            columns = list(rows[0]) if rows else ['id']
            db.execute(f'CREATE TABLE {table} ('+','.join('"'+k+'"' for k in columns)+')')
            for row in rows:
                db.execute(f'INSERT INTO {table} VALUES ('+','.join('?' for _ in columns)+')',list(row.values()))

class LegacyMigrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        vehicle = dict(id=1, immatriculation='TEST 0701', marque='Marque', modele="Modèle d'essai", annee=2020,
            type='Utilitaire', carburant='Diesel', num_chassis='TESTVIN0701', num_moteur='', cv=9, statut='En service',
            direction='Direction', affectation='Site', carte_grise='REF', created_at=STAMP, lat=None, lng=None,
            kilometrage=4000, vignette_exp='2027-01-01', assurance_exp=None, ctrl_tech_exp=None,carte_grise_exp=None,
            photo='missing.jpg', photo_thumb='')
        driver = dict(id=1, matricule='TEST-MAT-0701', nom='Test', prenom='Conducteur', telephone='+237600000000',
            email='driver@example.test', cni='', cni_exp=None, permis='TEST-LIC', permis_exp='2027-01-01',created_at=STAMP)
        self.data = dict(vehicles=[vehicle],drivers=[driver],
            users=[dict(id=1,username='test-user',email='user@example.test',password_hash='synthetic-hash',role='user',created_at=STAMP)],
            maintenance=[dict(id=1,vehicle_id=1,type='Freinage',date='2026-01-02',cout=1000,garage='Garage',notes='Freins',created_at=STAMP)],
            fuel_fillups=[dict(id=1,vehicle_id=1,date='2026-01-02',liters=10,amount=8000,price_l=800,station='Station',km=4000,driver_id=None,notes='',created_at=STAMP)],
            costs=[dict(id=1,vehicle_id=1,date='2026-01-02',amount=200,category='Autre',label='Frais',provider='',notes='',created_at=STAMP)],
            vidanges=[dict(id=1,vehicle_id=1,date='2026-01-02',km=4000,huile='Huile',quantite=5,filtre=1,filtre_huile=1,
                filtre_gasoil=0,huile_transmission=0,liquide_refroidissement=0,liquide_laveglace=0,km_interval=5000,prochaine_km=9000,
                cout=500,garage='Garage',created_at=STAMP)],
            controls=[dict(id=1,vehicle_id=1,driver_id=1,type='Contrôle technique',date='2026-01-02',resultat='Conforme',
                notes='Contrôle',items=json.dumps({'Pneumatiques et pression':'À surveiller'}),created_at=STAMP)],
            budget_plan=[dict(id=1,annee=2026,categorie_std='Freinage',activite='Entretien',tache="Budget d'essai",
                q1_montant=10000,q2_montant=0,q3_montant=0,q4_montant=0,montant_annuel=12000,created_at=STAMP)],
            audit_log=[dict(id=1,action='TEST',details='Historique')],settings=[dict(key='test',value='original')],
            chat_history=[dict(id=1,role='user',content='Message historique')],documents=[],odometer=[],tires=[])
        self.parc=self.root/'parc.db';self.console=self.root/'console.db'
        fixture(self.parc,self.data)
        fixture(self.console,{'console_users':[dict(id=1,username='test-console',password_hash='synthetic-hash',created_at=STAMP)]})

    def test_archive_is_complete_and_inputs_unchanged(self):
        before=[legacy.sha(p.read_bytes()) for p in [self.parc,self.console]]
        job=legacy.Import(self.parc,self.console);sql=job.prepare()
        self.assertEqual(sum(len(v) for v in self.data.values())+1,job.counts['legacy_import_record'])
        self.assertEqual(before,[legacy.sha(p.read_bytes()) for p in [self.parc,self.console]])
        self.assertIn("Modèle d''essai",sql)
        self.assertNotIn('synthetic-hash',sql[sql.index('INSERT INTO app_user'):sql.index('INSERT INTO vehicle ')])
        self.assertIn("ARRAY[]::text[]",sql)
        self.assertTrue(any(w['table']=='budget_plan' for w in job.warnings))
        self.assertEqual(job.batch,legacy.Import(self.parc,self.console).batch)

    @unittest.skipUnless(os.environ.get('NF_LEGACY_TEST_DSN'),'PostgreSQL integration requires NF_LEGACY_TEST_DSN')
    def test_postgres_import_retry_costs_and_changed_source_rollback(self):
        dsn=os.environ['NF_LEGACY_TEST_DSN']
        def psql(sql,ok=True):
            result=subprocess.run(['psql',dsn,'-X','-v','ON_ERROR_STOP=1','-v','org_id='+ORG,'-v','actor_id='+ACTOR,'-At'],
                input=sql,text=True,capture_output=True)
            if ok: self.assertEqual(0,result.returncode,result.stderr)
            else: self.assertNotEqual(0,result.returncode)
            return result.stdout.strip()
        psql(f"INSERT INTO organisation_unit(id,code,name) VALUES ('{ORG}','LEGACY-TEST','Migration test'); INSERT INTO app_user(id,login,display_name,org_unit_id) VALUES ('{ACTOR}','legacy-test-operator','Operator','{ORG}');")
        job=legacy.Import(self.parc,self.console);sql=job.prepare()
        psql(sql);psql(sql) # idempotent: repeated import does not duplicate money or identities
        self.assertEqual('9700.00',psql(f"SELECT sum(amount_xaf) FROM v_cost_entry WHERE org_unit_id='{ORG}';"))
        self.assertEqual('1',psql(f"SELECT count(*) FROM vehicle WHERE org_unit_id='{ORG}';"))
        self.assertEqual('2',psql(f"SELECT count(*) FROM app_user WHERE org_unit_id='{ORG}' AND status='SUSPENDED' AND password_hash IS NULL;"))
        self.assertEqual('1',psql(f"SELECT count(*) FROM inspection_item WHERE inspection_id='{legacy.uid('controls',1)}' AND point_code='PNEUMATIQUES';"))
        self.assertEqual('1',psql(f"SELECT count(*) FROM audit_event WHERE action='LEGACY_IMPORT' AND object_id='{job.batch}';"))
        # Raw historical audit and hashes are not exposed as operational records.
        self.assertEqual(str(job.counts['legacy_import_record']),psql(f"SELECT count(*) FROM legacy_import_record WHERE batch_id='{job.batch}';"))
        with sqlite3.connect(self.parc) as db: db.execute("UPDATE maintenance SET cout=99999")
        psql(legacy.Import(self.parc,self.console).prepare(),ok=False)
        self.assertEqual('9700.00',psql(f"SELECT sum(amount_xaf) FROM v_cost_entry WHERE org_unit_id='{ORG}';"))

if __name__=='__main__':unittest.main()
