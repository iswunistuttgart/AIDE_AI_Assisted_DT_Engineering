"""Container healthcheck: succeeds once the simulator reports the OPC UA server state Running."""

import asyncio
import os
import sys

from asyncua import Client, ua


async def running(endpoint: str) -> bool:
    async with Client(endpoint, timeout=3) as client:
        state = await client.get_node(ua.ObjectIds.Server_ServerStatus_State).read_value()
    return state == ua.ServerState.Running


sys.exit(0 if asyncio.run(running(os.environ["OPCUA_HEALTHCHECK_ENDPOINT"])) else 1)
