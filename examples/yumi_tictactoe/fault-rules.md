# Fault rules of the YuMi tic-tac-toe demonstrator

The robot reports its state as idle, moving while it executes a move, stopped after a stop,
or error. Services may only call the methods of the robot: placeX and placeO with the target
cell, clearBoard, stop and start. start is only possible after a preceding stop.

1. Motor overheating: If the motor temperature of axis 3 rises above 80 °C, call stop, and
   call start once the temperature falls below 60 °C.

2. Error state: If the robot reports an error state, call stop and ask the operator whether
   to call clearBoard and start.

3. Move not completed: If no move completes within 30 seconds after placeX or placeO, call
   stop and escalate to the operator.
