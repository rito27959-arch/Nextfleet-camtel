#!/usr/bin/env python3
"""Prepare an atomic, repeatable SQLite -> PostgreSQL import. Never connects to production.
The SQL requires psql variables org_id and actor_id, and migration V10. Inputs are read-only.
Raw data/password hashes stay in the private archive; never commit generated output.
"""
import argparse
import base64
from collections import Counter
from datetime import date, datetime
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import unicodedata
import uuid
from zoneinfo import ZoneInfo

NS = uuid.UUID('8d3a74bb-287b-4667-869c-19d03842f1cf')
ORG = '(SELECT org_id FROM nf_import_context)'
ACTOR = '(SELECT actor_id FROM nf_import_context)'
DOUALA = ZoneInfo('Africa/Douala')

class Expr(str):
    pass

def uid(table, key, source='parc.db'):
    return str(uuid.uuid5(NS, f'{source}:{table}:{key}'))

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)

def sha(value):
    return hashlib.sha256(value).hexdigest()

def quote(value):
    if isinstance(value, Expr):
        return str(value)
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, (int, Decimal)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"

def text(value):
    return str(value).strip() if value is not None and str(value).strip() else None

def day(value):
    return date.fromisoformat(str(value)[:10]).isoformat() if text(value) else None

def instant(value):
    if not text(value):
        return None
    t = datetime.fromisoformat(str(value))
    return (t.replace(tzinfo=DOUALA) if t.tzinfo is None else t).isoformat()

def money(value):
    return Decimal(str(value or 0)).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)

def category(value):
    v = ''.join(c for c in unicodedata.normalize('NFD', str(value or '')) if not unicodedata.combining(c)).upper()
    return v if v in {'MOTEUR','FREINAGE','POMPES','ADMINISTRATIF','VIDANGE','PNEUMATIQUE','TOLERIE','BATTERIE','ELECTRICITE'} else 'AUTRE'

def snapshot(path):
    path = Path(path).resolve()
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError(f'Integrity check failed: {path.name}')
        if db.execute('PRAGMA foreign_key_check').fetchall():
            raise ValueError(f'Broken source foreign keys: {path.name}')
        schemas = [dict(r) for r in db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name")]
        tables = {}
        for schema in schemas:
            name = schema['name']
            escaped = name.replace('"', '""')
            rows = []
            for row in db.execute(f'SELECT * FROM "{escaped}"'):
                rows.append({k: {'$base64': base64.b64encode(v).decode()} if isinstance(v, bytes) else v for k,v in dict(row).items()})
            tables[name] = rows
    return tables, {'sha256': sha(path.read_bytes()), 'bytes': path.stat().st_size, 'schema': schemas,
                    'counts': {k:len(v) for k,v in tables.items()}}

class Import:
    def __init__(self, parc, console):
        self.data = {}; self.manifest = {}; self.lines = []; self.warnings = []; self.counts = Counter()
        for source,path in [('parc.db',parc),('console.db',console)]:
            self.data[source], self.manifest[source] = snapshot(path)
        self.batch = uid('batch', canonical(self.manifest))
        self.captured_at = datetime.now(DOUALA).isoformat()

    def insert(self, table, values, conflict='id'):
        self.lines.append(f"INSERT INTO {table} ({','.join(values)}) VALUES ({','.join(quote(v) for v in values.values())}) ON CONFLICT ({conflict}) DO NOTHING;")
        self.counts[table] += 1

    def warn(self, table, row, message):
        self.warnings.append({'table':table, 'id':row.get('id'), 'detail':message})

    def prepare(self):
        self.lines = ["\\set ON_ERROR_STOP on", 'BEGIN;', "SET LOCAL TIME ZONE 'Africa/Douala';", 'SET LOCAL standard_conforming_strings = on;',
                      'SELECT pg_advisory_xact_lock(741921083);',
                      'CREATE TEMP TABLE nf_import_context(org_id uuid, actor_id uuid) ON COMMIT DROP;',
                      "INSERT INTO nf_import_context VALUES (:'org_id'::uuid, :'actor_id'::uuid);",
                      "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM organisation_unit o, nf_import_context c WHERE o.id=c.org_id AND NOT o.is_demo) OR NOT EXISTS(SELECT 1 FROM app_user u, nf_import_context c WHERE u.id=c.actor_id AND u.status='ACTIVE' AND NOT u.is_demo) THEN RAISE EXCEPTION 'Select an existing production organisation and importing operator'; END IF; END $$;",
                      'CREATE TEMP TABLE nf_import_expected(id uuid, sha256 text) ON COMMIT DROP;']
        self.insert('legacy_import_batch', {'id':self.batch, 'manifest':canonical(self.manifest), 'org_unit_id':Expr(ORG), 'imported_by':Expr(ACTOR)})
        self.lines.append(f"DO $$ BEGIN IF EXISTS(SELECT 1 FROM legacy_import_batch WHERE id={quote(self.batch)} AND org_unit_id <> {ORG}) THEN RAISE EXCEPTION 'This snapshot was imported into a different organisation'; END IF; END $$;")
        for source,tables in self.data.items():
            for table,rows in tables.items():
                for index,row in enumerate(rows):
                    key = str(row.get('id', row.get('key', row.get('name', index))))
                    payload = canonical(row); digest = sha(payload.encode())
                    rid = uid(table,key,source)
                    self.insert('legacy_import_record', {'id':rid,'batch_id':self.batch,'source_db':source,'source_table':table,'source_key':key,'payload':payload,'sha256':digest})
                    self.lines.append(f'INSERT INTO nf_import_expected VALUES ({quote(rid)},{quote(digest)});')
        # A revised snapshot cannot silently overwrite an earlier migration.
        self.lines.append("DO $$ BEGIN IF EXISTS(SELECT 1 FROM nf_import_expected e LEFT JOIN legacy_import_record r USING(id) WHERE r.id IS NULL OR r.sha256 <> e.sha256) THEN RAISE EXCEPTION 'Source changed since earlier import: reconciliation required'; END IF; END $$;")
        p = self.data['parc.db']
        for source,table in [('parc.db','users'),('console.db','console_users')]:
            for r in self.data[source].get(table,[]):
                # The old 'user' role does not prove DRIVER privileges; do not overwrite production logins/passwords.
                login = 'legacy.' + source.split('.')[0] + '.' + r['username']
                self.insert('app_user', {'id':uid(table,r['id'],source),'login':login,'display_name':r['username'],
                    'email':r.get('email'),'org_unit_id':Expr(ORG),'status':'SUSPENDED','must_change_password':True,
                    'created_at':instant(r['created_at'])})
                self.warn(table,r,'Compte importé sous '+login+' ; réinitialisation du mot de passe, rôle et activation à valider. Hash original conservé dans l’archive privée.')
        for r in p['vehicles']:
            vehicle = uid('vehicles',r['id'])
            values = {'id':vehicle,'registration':r['immatriculation'],'make':r['marque'],'model':r['modele'],
                'model_year':r['annee'] or None,'vehicle_type':r['type'],'energy':{'Diesel':'DIESEL','Essence':'ESSENCE'}.get(r['carburant'],'AUTRE'),
                'vin':text(r['num_chassis']),'engine_number':text(r['num_moteur']),'fiscal_hp':r['cv'] or None,
                'status':{'En service':'IN_SERVICE','En maintenance':'MAINTENANCE','En panne':'OUT_OF_SERVICE'}[r['statut']],
                'org_unit_id':Expr(ORG),'direction':r['direction'],'site_label':r['affectation'],'registration_card_no':r['carte_grise'],
                'created_at':instant(r['created_at'])}
            if r['lat'] is not None and r['lng'] is not None:
                values.update(declared_lat=Decimal(str(r['lat'])),declared_lng=Decimal(str(r['lng'])),declared_at=self.captured_at)
                self.warn('vehicles',r,'Position déclarée issue du snapshot, jamais présentée comme une position GPS en direct.')
            self.insert('vehicle',values)
            self.insert('vehicle_odometer', {'id':uid('vehicle_snapshot_km',r['id']),'vehicle_id':vehicle,'read_at':self.captured_at,
                'value_km':r['kilometrage'] or 0,'source':'IMPORT','author_id':Expr(ACTOR),'justification':'Compteur présent dans le snapshot parc.db ; date réelle du relevé non disponible.'})
            for col,kind in [('vignette_exp','VIGNETTE'),('assurance_exp','ASSURANCE'),('ctrl_tech_exp','CONTROLE_TECHNIQUE'),('carte_grise_exp','CARTE_GRISE')]:
                if text(r[col]):
                    self.insert('vehicle_document',{'id':uid('document_'+col,r['id']),'vehicle_id':vehicle,'doc_type':kind,'valid_until':day(r[col]),'created_at':instant(r['created_at'])})
            if text(r['photo']) or text(r['photo_thumb']):
                self.warn('vehicles',r,'Chemins photo conservés ; fichiers image absents des deux bases SQLite.')
        for r in p['drivers']:
            driver = uid('drivers',r['id'])
            if not text(r['matricule']):
                raise ValueError(f"drivers/{r['id']}: matricule manquant, rapprochement manuel requis")
            self.insert('driver',{'id':driver,'staff_number':r['matricule'],'full_name':(r['prenom']+' '+r['nom']).strip(),
                'phone':r['telephone'],'email':r['email'],'org_unit_id':Expr(ORG),'id_card_number':r['cni'],
                'id_card_expires_on':day(r['cni_exp']),'created_at':instant(r['created_at'])})
            if text(r['permis']) and day(r['permis_exp']):
                self.insert('driver_license',{'id':uid('driver_license',r['id']),'driver_id':driver,'license_number':r['permis'],
                    'categories':Expr("ARRAY[]::text[]"),'expires_on':day(r['permis_exp'])})
                self.warn('drivers',r,'Catégories du permis absentes de la source : laissées vides. Lien avec un compte à confirmer explicitement.')
        for r in p.get('odometer',[]):
            self.insert('vehicle_odometer', {'id':uid('odometer',r['id']),'vehicle_id':uid('vehicles',r['vehicle_id']),
                'read_at':instant(r['date']),'value_km':r['kilometres'],'source':'IMPORT','author_id':Expr(ACTOR),
                'justification':r['note'],'created_at':instant(r['created_at'])})
        for r in p['vidanges']:
            interval = r['km_interval'] or ((r['prochaine_km']-r['km']) if r['prochaine_km'] and r['prochaine_km']>r['km'] else None)
            self.insert('oil_change',{'id':uid('vidanges',r['id']),'vehicle_id':uid('vehicles',r['vehicle_id']),'done_on':day(r['date']),
                'odometer_km':r['km'],'oil_label':r['huile'],'oil_liters':money(r['quantite']),
                'oil_filter':bool(r['filtre_huile'] or r['filtre']),'fuel_filter':bool(r['filtre_gasoil']),
                'transmission_oil':bool(r['huile_transmission']),'coolant':bool(r['liquide_refroidissement']),
                'washer_fluid':bool(r['liquide_laveglace']),'interval_km':interval,'cost_xaf':money(r['cout']),
                'garage_label':r['garage'],'author_id':Expr(ACTOR),'created_at':instant(r['created_at'])})
        points = ['CARROSSERIE','PNEUMATIQUES','FREINS','ECLAIRAGE','NIVEAUX','BATTERIE','ESSUIE_GLACES','SUSPENSION','DOCUMENTS','SECURITE','CLIMATISATION','SECOURS']
        # Keys are matched by full legacy label, never by dict iteration order.
        labels = ['Carrosserie et état extérieur','Pneumatiques et pression','Freins et plaquettes','Éclairage et signalisation','Niveaux (huile, refroidissement)','Batterie et démarrage','Essuie-glaces et lave-glace','Suspension et direction','Documents de bord','Sécurité (triangle, trousse)','Climatisation / chauffage','Roue de secours / kit']
        for r in p['controls']:
            inspection = uid('controls',r['id'])
            self.insert('inspection',{'id':inspection,'vehicle_id':uid('vehicles',r['vehicle_id']),
                'driver_id':uid('drivers',r['driver_id']) if r['driver_id'] else None,
                'kind':{'Contrôle de départ':'AVANT_MISSION','Contrôle technique':'PERIODIQUE'}.get(r['type'],'PERIODIQUE'),
                'inspected_at':instant(r['date']),'result':{'Conforme':'CONFORME','Réserves':'RESERVES','Non conforme':'NON_CONFORME'}[r['resultat']],
                'notes':r['notes'],'author_id':Expr(ACTOR),'created_at':instant(r['created_at'])})
            for label,state in json.loads(r['items'] or '{}').items():
                if label not in labels or state not in ['Conforme','À surveiller']:
                    self.warn('controls',r,'Point de contrôle non convertible conservé dans l’archive : '+label)
                    continue
                self.insert('inspection_item',{'inspection_id':inspection,'point_code':points[labels.index(label)],
                    'state':'OK' if state=='Conforme' else 'A_SURVEILLER'},'inspection_id,point_code')
        for table,kind in [('maintenance','MAINTENANCE'),('fuel_fillups','FUEL'),('costs','EXPENSE')]:
            for r in p[table]:
                self.insert('legacy_operation',{'id':uid(table,r['id']),'org_unit_id':Expr(ORG),
                    'vehicle_id':uid('vehicles',r['vehicle_id']) if r['vehicle_id'] else None,'kind':kind,'occurred_on':day(r['date']),
                    'category_code':category(r.get('type',r.get('category'))),'label':r.get('label',r.get('type','Plein importé')),
                    'amount_xaf':money(r.get('cout',r.get('amount'))),'liters':Decimal(str(r['liters'])) if 'liters' in r else None,
                    'odometer_km':r.get('km'),'provider':r.get('garage',r.get('station',r.get('provider'))),'notes':r.get('notes')})
        for r in p['budget_plan']:
            amounts = [money(r[f'q{i}_montant']) for i in range(1,5)]
            if sum(amounts)!=money(r['montant_annuel']):
                self.warn('budget_plan',r,f"Annuel source {money(r['montant_annuel'])} ; somme des trimestres {sum(amounts)}. Trimestres conservés ; valeur annuelle source dans l’archive.")
            self.insert('budget_plan_line',{'id':uid('budget_plan',r['id']),'fiscal_year':r['annee'],'org_unit_id':Expr(ORG),
                'category_code':category(r['categorie_std']),'activity':r['activite'],'task':r['tache'],
                **{f'q{i+1}_amount':v for i,v in enumerate(amounts)},'source_file':'parc.db/budget_plan','source_row':r['id'],
                'import_batch_id':self.batch,'created_at':instant(r['created_at'])})
        self.lines.append(f"INSERT INTO audit_event(actor_id,action,object_type,object_id,new_value) SELECT {ACTOR},'LEGACY_IMPORT','legacy_import_batch',{quote(self.batch)},{quote(canonical(dict(self.counts)))}::jsonb WHERE NOT EXISTS(SELECT 1 FROM audit_event WHERE action='LEGACY_IMPORT' AND object_id={quote(self.batch)});")
        self.lines.append('COMMIT;')
        return '\n'.join(self.lines)+'\n'

    def report(self):
        return {'batch_id':self.batch,'source_manifest':self.manifest,'planned_inserts':dict(self.counts),
            'warnings':self.warnings,'notes':[
                'Tous les enregistrements et schémas SQLite sont archivés, y compris audit, chat, paramètres et sqlite_sequence.',
                'Véhicules, conducteurs, échéances, kilométrages, contrôles, vidanges et budgets sont convertis dans les tables métier.',
                'Maintenance, pleins et dépenses historiques sont visibles via legacy_operation et inclus dans les coûts, sans fausse validation.',
                'Comptes historiques suspendus et sans rôle ; les comptes de production existants restent intacts.',
                'Aucun rapprochement compte/conducteur basé sur le nom ; lien explicite dans l’application.',
                'Les champs sans équivalent, références de fichiers et valeurs originales restent dans legacy_import_record.',
                'La première tentative sur une base cible doit se faire sur une copie sauvegardée. Une collision métier annule toute la transaction.' ]}

def main():
    a=argparse.ArgumentParser(description=__doc__)
    a.add_argument('--parc',required=True,type=Path);a.add_argument('--console',required=True,type=Path)
    a.add_argument('--output',required=True,type=Path)
    args=a.parse_args()
    os.umask(0o077)
    job=Import(args.parc,args.console)
    sql=job.prepare()
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'import.sql').write_text(sql,encoding='utf-8')
    (args.output/'report.json').write_text(json.dumps(job.report(),ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'batch_id':job.batch,'counts':dict(job.counts),'warnings':len(job.warnings)},ensure_ascii=False))

if __name__=='__main__':
    main()
