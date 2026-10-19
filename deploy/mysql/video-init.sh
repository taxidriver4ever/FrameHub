#!/bin/sh
set -eu
# Restrict interpolated identifiers and passwords; generated credentials use base64.
case "$VIDEO_DB_PASSWORD$CANAL_PASSWORD" in *[!A-Za-z0-9+/=._-]*) echo 'Unsupported credential characters' >&2; exit 1;; esac
[ "${#CANAL_PASSWORD}" -le 32 ] || { echo "Canal replication password must be at most 32 characters" >&2; exit 1; }
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql -h mysql -u root <<SQL
CREATE DATABASE IF NOT EXISTS framehub_video CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'framehub_video'@'%' IDENTIFIED BY '$VIDEO_DB_PASSWORD';
ALTER USER 'framehub_video'@'%' IDENTIFIED BY '$VIDEO_DB_PASSWORD';
GRANT ALL PRIVILEGES ON framehub_video.* TO 'framehub_video'@'%';
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED WITH mysql_native_password BY '$CANAL_PASSWORD';
ALTER USER 'canal'@'%' IDENTIFIED WITH mysql_native_password BY '$CANAL_PASSWORD';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
SQL

# On first deployment replay from the oldest retained binlog, not a late startup timestamp.
first_log=$(mysql -h mysql -u root --batch --skip-column-names -e 'SHOW BINARY LOGS' | awk 'NR==1 {print $1}')
mkdir -p /canal-state
printf 'canal.instance.master.journal.name=%s\ncanal.instance.master.position=4\ncanal.instance.master.timestamp=\n' "$first_log" > /canal-state/bootstrap.properties
