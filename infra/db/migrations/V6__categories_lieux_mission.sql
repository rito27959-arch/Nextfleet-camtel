-- NexFleet CAMTEL — V6 : catégorie de véhicule (silhouette 3D du catalogue) et lieux de mission affichés aux clients.
ALTER TABLE vehicle ADD COLUMN category text
    CHECK (category IS NULL OR category IN ('Pick-up','SUV','4x4','Berline','Minibus','Fourgon','Camion','Moto','Autre'));
ALTER TABLE mission ADD COLUMN origin_label text, ADD COLUMN destination_label text;
-- Un démarrage ne peut se faire qu'avec une notice d'information versionnée enregistrée (NF-70).
ALTER TABLE tracking_authorization ADD CONSTRAINT ck_notice_version CHECK (notice_version ~ '^NOTICE-GPS-v[0-9]+$');
