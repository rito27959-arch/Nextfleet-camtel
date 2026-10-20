# Traccar Server avec la configuration NexFleet (forward JSON vers la passerelle). Version figée après qualification DSIR.
FROM traccar/traccar:6.16.0-debian
COPY infra/traccar/traccar.docker.xml /opt/traccar/conf/traccar.xml
