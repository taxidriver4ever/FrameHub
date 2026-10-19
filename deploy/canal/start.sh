#!/bin/sh
set -eu
cd /home/admin/canal-server
# Keep position metadata in the durable instance directory across restarts.
mkdir -p conf/video
[ -f conf/canal.properties.defaults ] || cp conf/canal.properties conf/canal.properties.defaults
cp conf/canal.properties.defaults conf/canal.properties
cat >> conf/canal.properties <<EOF
canal.id=1
canal.ip=
canal.port=11111
canal.metrics.pull.port=11112
canal.serverMode=kafka
canal.destinations=video
canal.conf.dir=../conf
canal.auto.scan=false
canal.instance.global.mode=spring
canal.instance.global.lazy=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml
canal.zkServers=
canal.file.data.dir=/home/admin/canal-server/conf
canal.auto.reset.latest.pos.mode=false
canal.mq.flatMessage=true
canal.mq.canalBatchSize=1
canal.mq.send.thread.size=1
canal.mq.build.thread.size=1
kafka.bootstrap.servers=kafka:9092
kafka.acks=all
kafka.compression.type=none
kafka.retries=10
kafka.max.in.flight.requests.per.connection=1
kafka.enable.idempotence=true
EOF
cp conf/example/instance.properties conf/video/instance.properties
cat conf/video/bootstrap.properties >> conf/video/instance.properties
cat >> conf/video/instance.properties <<EOF
canal.instance.mysql.slaveId=12345
canal.instance.master.address=mysql:3306
canal.instance.dbUsername=canal
canal.instance.dbPassword=$CANAL_PASSWORD
canal.instance.connectionCharset=UTF-8
canal.instance.defaultDatabaseName=framehub_video
canal.instance.filter.regex=framehub_video\\\\.outbox_event
canal.instance.filter.black.regex=mysql\\\\..*
canal.instance.tsdb.enable=true
canal.mq.topic=video.outbox.cdc
canal.mq.partitionsNum=3
canal.mq.partitionHash=framehub_video.outbox_event:aggregate_id
EOF
mkdir -p logs/canal logs/video
cd bin
exec java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -DappName=otter-canal -Dlogback.configurationFile=/home/admin/canal-server/conf/logback.xml -Dcanal.conf=/home/admin/canal-server/conf/canal.properties -classpath '/home/admin/canal-server/conf:/home/admin/canal-server/lib/*' com.alibaba.otter.canal.deployer.CanalLauncher
