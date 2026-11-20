"""Entrypoint of the Home Assistant container.

Before Home Assistant starts, it registers the MQTT broker with the account of Home Assistant as
the MQTT integration, so entities of the adapter appear without manual setup, and writes an empty
dashboard that the first deployment replaces.
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path

CONFIG = Path("/config")
BROKER = {
    "broker": "mosquitto",
    "port": 1883,
    "username": "homeassistant",
    "password": Path("/run/mqtt-secrets/homeassistant").read_text(encoding="utf-8").strip(),
}
EMPTY_DASHBOARD = """\
title: Digital Twin
views:
  - title: Not deployed
    cards:
      - type: markdown
        content: Deploy the digital twin in AIDE to fill this dashboard.
"""


def register_broker() -> None:
    path = CONFIG / ".storage" / "core.config_entries"
    if path.exists():
        store = json.loads(path.read_text(encoding="utf-8"))
    else:
        store = {"version": 1, "minor_version": 1, "key": "core.config_entries", "data": {"entries": []}}
    now = datetime.now(timezone.utc).isoformat()
    entries = store["data"]["entries"]
    entry = next((e for e in entries if e["domain"] == "mqtt"), None)
    if entry is None:
        # Fields of a stored config entry, as Home Assistant writes them itself.
        entry = dict(entry_id="aide_mqtt", domain="mqtt", version=1, minor_version=2, source="user",
                     unique_id=None, disabled_by=None, discovery_keys={}, subentries=[],
                     pref_disable_new_entities=False, pref_disable_polling=False, created_at=now,
                     options={"discovery": True, "discovery_prefix": "homeassistant"})
        entries.append(entry)
    entry |= {"data": BROKER, "title": BROKER["broker"], "modified_at": now}
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(store), encoding="utf-8")


register_broker()
dashboard = CONFIG / "dt-dashboard.yaml"
if not dashboard.exists():
    dashboard.write_text(EMPTY_DASHBOARD, encoding="utf-8")
os.execv("/init", ["/init"])
