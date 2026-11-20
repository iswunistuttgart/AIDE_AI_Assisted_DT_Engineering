#!/bin/sh
# Starts the MQTT broker of the deployed digital twin. Every client has its own account, whose
# password the mqtt-secrets service generated, and the ACL limits each account to its topics.
set -eu

auth=/run/mosquitto-auth
mkdir -p "$auth"
umask 077
# A restarted container still has the password file of its last start; mosquitto_passwd -c
# refuses to overwrite it, so the file is written anew on every start.
rm -f "$auth/passwords"

flag=-c  # The first account creates the password file.
for account in homeassistant adapter observer; do
  mosquitto_passwd $flag -b "$auth/passwords" "$account" "$(cat "/run/mqtt-secrets/$account")"
  flag=
done

cat > "$auth/acl" <<'ACL'
# Home Assistant reads entities and states and sends operator commands.
user homeassistant
topic read homeassistant/#
topic write homeassistant/status
topic read dt/#
topic write dt/command/#

# Only the adapter publishes entities, machine values, unavailable (unbound) values and watchdog states.
user adapter
topic write homeassistant/#
topic write dt/state/#
topic write dt/unbound/#
topic write dt/watchdog/#
topic readwrite dt/adapter/#
topic read dt/command/#

# The observer (healthcheck, diagnostics) only reads.
user observer
topic read #
topic read $SYS/#
ACL

chown -R mosquitto:mosquitto "$auth" /mosquitto/data /mosquitto/log
exec mosquitto -c /aide/mosquitto.conf
