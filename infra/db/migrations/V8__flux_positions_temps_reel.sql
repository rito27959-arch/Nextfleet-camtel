-- V8 : diffusion temps réel des positions ACCEPTÉES (LISTEN/NOTIFY). La notification part au COMMIT du point :
-- un point refusé ou annulé n'est jamais diffusé. Le serveur filtre ensuite par droits avant tout envoi aux clients.
CREATE FUNCTION nf_notify_position() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m record;
BEGIN
    SELECT mi.org_unit_id, mi.reference, d.user_id AS driver_user INTO m
      FROM mission mi JOIN driver d ON d.id = mi.driver_id WHERE mi.id = NEW.mission_id;
    PERFORM pg_notify('nf_position', json_build_object(
        'missionId', NEW.mission_id, 'missionRef', m.reference, 'vehicleId', NEW.vehicle_id,
        'orgUnitId', m.org_unit_id, 'driverUserId', m.driver_user,
        'lat', ST_Y(NEW.position::geometry), 'lon', ST_X(NEW.position::geometry),
        'accuracyM', NEW.accuracy_m, 'speedMps', NEW.speed_mps, 'bearingDeg', NEW.bearing_deg,
        'recordedAt', to_char(NEW.recorded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'source', NEW.source_device_id)::text);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_location_point_notify AFTER INSERT ON location_point
    FOR EACH ROW EXECUTE FUNCTION nf_notify_position();

-- Dernière position acceptée par véhicule (lecture rapide de la carte de flotte).
CREATE INDEX ix_location_vehicle_time ON location_point(vehicle_id, recorded_at DESC);
