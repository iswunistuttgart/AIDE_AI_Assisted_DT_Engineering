# Simulator Scenarios

This document belongs to the [README](../README.md) of AIDE. It lists the simulator scenarios of the
examples and how the watchdogs react to them.

Each simulator adds small noise to the measured values every 10 s (the tool life of the milling
machine also decreases slowly), so the dashboard values change. A scenario adds faults. In the
*Deploy & monitor* panel, choose a **Simulator scenario** and click **Start scenario**. The
simulator first resets every value to its NodeSet value, so each run starts from the same state,
and then changes values at fixed times (`simulation.json` of the example). The watchdogs
(`watchdog.wf`) react through the adapter. The panel shows the expected watchdog behavior of the
selected scenario. Each example has one scenario per path of its watchdogs.

## YuMi tic-tac-toe

| Scenario | What the simulator does | How the watchdog reacts | Operator action |
|---|---|---|---|
| `idle` | Nothing; the robot stays idle. | No reaction. | Play yourself in Home Assistant with `nextX`, `nextO`, `clear`, `stop` and `restart`. |
| `overheat` | Axis 3: 70 °C after 10 s, 84 °C after 20 s, 65 °C after 60 s, 55 °C after 90 s. | *MotorOverheat* calls `Stop` above 80 °C, waits, and calls `Start` once the axis is below 60 °C. | None. |
| `overheat-persists` | Axis 3 rises to 85 °C after 10 s and stays there. | *MotorOverheat* calls `Stop`; the axis does not cool below 60 °C, so the watchdog escalates after 10 minutes and the robot stays stopped. | None; wait for the escalation. |
| `error` | The robot state changes to `error` after 10 s. | *ErrorState* calls `Stop` and asks "Clear the board and restart it?". **Yes** calls `Start` and then `ClearBoard`; **No** ends the watchdog with the robot stopped; no answer within 5 minutes escalates. | answer the task in [Camunda Tasklist](http://localhost:8180/tasklist). |
| `move-stuck` | The robot state changes to `moving` after 10 s and never returns to `idle`. | *MoveNotCompleted* waits 30 s for the move, then calls `Stop` and escalates. | None. |

A normal move, such as `nextX` in Home Assistant, returns to `idle` after 4 s, so
*MoveNotCompleted* ends without an alarm. The restart button appears only while the robot is
stopped. A move on an occupied cell is rejected with the state `error`, which starts *ErrorState*
as in the `error` scenario, until `clear` empties the board; the *Deploy & monitor* panel shows the
rejection as the last error.

## Robot cell and milling machine

A selection; the *Deploy & monitor* panel lists all scenarios with their expected behavior.

| Example | Scenario | Expected watchdog behavior |
|---|---|---|
| Robot cell | `spindle-hot` | The threshold table sets 60 mm/s, the spindle cools down, the watchdog ends |
| Robot cell | `conveyor-jam-cleared` | The conveyor stops and the robot slows down to 10 % at the same time; when the conveyor runs again, the robot returns to 100 % |
| Robot cell | `axis-hot` | The minute check sets the override to 50 % and asks whether to stop the robot |
| Milling machine | `chatter-persists` | 80 % feed does not help; the called watchdog SafeStop activates the feed hold and asks whether to abort the program |
| Milling machine | `rotary-table-hot` | Feed override and coolant boost in parallel cool the rotary table down |
