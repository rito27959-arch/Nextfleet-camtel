# Hébergement NexFleet sur un serveur CAMTEL

Kit prêt à lancer par la DSIR. Il démarre la base PostgreSQL/PostGIS, l'API, la passerelle GPS, Traccar, un frontal
HTTPS (Caddy) et une sauvegarde quotidienne. Les images viennent de la CI GitHub (`ghcr.io/rito27959-arch/…`), testées
à chaque version.

## Prérequis

- Serveur Linux 64 bits (Ubuntu 22.04/24.04 ou Debian 12), 2 vCPU, 4 Go de RAM, 40 Go de disque.
- Docker Engine 24+ avec Compose v2.
- Deux noms DNS pointant vers le serveur : un pour l'API (`nexfleet.camtel.cm`), un pour la passerelle GPS
  (`gps.nexfleet.camtel.cm`).
- Ports 80 et 443 ouverts depuis Internet (ou depuis le réseau des téléphones de mission, via VPN/APN privé).

## Installation (15 minutes)

```bash
sudo mkdir -p /opt/nexfleet && cd /opt/nexfleet
curl -fsSLO https://raw.githubusercontent.com/rito27959-arch/Nextfleet-camtel/main/infra/deploy/camtel/compose.yaml
curl -fsSLO https://raw.githubusercontent.com/rito27959-arch/Nextfleet-camtel/main/infra/deploy/camtel/Caddyfile
curl -fsSLO https://raw.githubusercontent.com/rito27959-arch/Nextfleet-camtel/main/infra/deploy/camtel/backup.sh
curl -fsSL  https://raw.githubusercontent.com/rito27959-arch/Nextfleet-camtel/main/infra/deploy/camtel/.env.example -o .env
chmod 600 .env && nano .env            # domaines + secrets (openssl rand -base64 48)
docker compose up -d
docker compose ps                      # tous « healthy » / « running »
curl https://nexfleet.camtel.cm/actuator/health   # {"status":"UP"}
```

Au premier démarrage, l'API applique les migrations, déclare l'environnement et crée le compte administrateur
technique (`NF_BOOTSTRAP_ADMIN_LOGIN`). L'administrateur crée ensuite les comptes et rôles. Il change son mot de passe
initial. Il ne dispose d'aucun droit métier (séparation des pouvoirs).

## Clients

- **Bureau** (DEB/EXE de la release) : `NexFleet --api=https://nexfleet.camtel.cm/`. Option `--tiles=` pour un serveur
  de tuiles CAMTEL.
- **Android** : sur l'écran de connexion, mode Serveur, adresse `https://nexfleet.camtel.cm`.

## Exploitation

| Besoin | Commande |
|---|---|
| Mise à jour | changer `NF_VERSION` dans `.env`, puis `docker compose pull && docker compose up -d` |
| Journaux | `docker compose logs -f api gps-gateway` |
| Sauvegardes | `ls backups/` : une par jour à 02:30, conservées 14 jours. Copier hors serveur (politique DSIR) |
| Restauration | `docker compose exec -T db pg_restore -U nexfleet -d nexfleet --clean < backups/nexfleet-….dump` |
| Certificat CAMTEL au lieu de Let's Encrypt | dans le `Caddyfile`, ajouter `tls /certs/fullchain.pem /certs/privkey.pem` et monter `/certs` |
| Console Traccar (admin GPS) | non exposée. Y accéder par tunnel : `ssh -L 8082:localhost:8082` puis `docker compose port traccar 8082` |

## Sécurité appliquée par le kit

- La base de données, Traccar et le canal Traccar → passerelle ne sont jamais exposés hors du réseau Docker interne.
- HTTPS obligatoire, HSTS, aucun en-tête de version serveur. Seule la santé de l'Actuator est publique.
- Les secrets restent dans `.env` (droits 600), jamais dans le dépôt ni dans les images.
- La passerelle GPS n'accepte publiquement que les lots Android signés par un jeton de mission.

## Ce qui reste à décider par la DSIR

Les noms DNS définitifs. Le certificat : Let's Encrypt ou PKI CAMTEL. La copie des sauvegardes hors site. La
supervision : l'Actuator expose `/actuator/health`. L'ouverture éventuelle des ports des boîtiers GPS véhicules.
