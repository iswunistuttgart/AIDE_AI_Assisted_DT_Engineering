# Container configuration

`compose.yaml` at the repository root defines the stack. [README.md](../README.md)
describes how to start it. This directory holds what the stock images need:

| File | Used by |
|---|---|
| `mosquitto/start.sh`, `mosquitto/mosquitto.conf` | MQTT broker: creates the accounts and the ACL, then starts Mosquitto |
| `homeassistant_start.py` | Home Assistant: registers the MQTT broker and an empty dashboard before the first start |
| `homeassistant/configuration.yaml` | Home Assistant: the dashboard `digital-twin`, which deployment fills |
| `camunda/application-h2.yaml` | Camunda 8: H2 storage, `demo` / `demo` login, REST API without OAuth |

The backend image is built from the repository root, because it compiles the
MontiCore languages in `languages/`. The root `.dockerignore` limits that build
context to the backend and the languages.

## MQTT accounts

Each MQTT client has its own account: `homeassistant`, `adapter`, and the
read-only `observer` for the broker healthcheck. On the first start, the service
`mqtt-secrets` generates a random password per account in the volume
`mqtt_secrets`. The broker, the adapter and Home Assistant read the passwords
from there. Only the adapter may publish entities, machine values and watchdog
states. Home Assistant may only publish operator commands.

The first visit to Home Assistant asks you to
[create a local owner account](https://www.home-assistant.io/getting-started/onboarding/).

## OPC UA endpoints

| Example | Endpoint for deployment (container network) | Endpoint for a desktop OPC UA client |
|---|---|---|
| Robot cell | `opc.tcp://opcua-robot-cell:4840/robot-cell/` | `opc.tcp://localhost:4841/robot-cell/` |
| Milling machine | `opc.tcp://opcua-milling-machine:4840/milling-machine/` | `opc.tcp://localhost:4842/milling-machine/` |
| YuMi tic-tac-toe | `opc.tcp://opcua-yumi:4840/yumi/` | `opc.tcp://localhost:4843/yumi/` |

The simulators accept anonymous clients without security. Each one serves the
NodeSet and `simulation.json` of its example. The backend selects a scenario by
writing `select.json` to `generated/runtime/scenario/<example>/`. To use real
equipment, deploy with its endpoint; the adapter checks each bound node before
it writes.

## State

Home Assistant keeps its accounts and storage in `docker/homeassistant/`, which
Git ignores. MQTT and Camunda use named volumes. The engineering workspace and
the active deployment are in `generated/workspace/` and `generated/runtime/`.
`docker compose down` keeps all of them.
