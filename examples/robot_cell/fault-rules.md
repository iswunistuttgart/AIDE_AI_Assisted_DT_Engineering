# Fault rules of the robot machine-tending cell

1. Spindle overheating: If the spindle temperature of the CNC machine stays above
   80 °C for 30 seconds, set the conveyor target speed according to the temperature:
   30 mm/s above 95 °C, 60 mm/s above 85 °C, and 90 mm/s otherwise. If the temperature
   does not drop below 70 °C within two minutes, ask the operator whether to trigger
   the emergency stop. If the operator does not answer within one minute, trigger it
   anyway. Inform the shift supervisor in any case.

2. Conveyor jam: If the motor current of the infeed conveyor stays above 8 A for
   5 seconds, stop the conveyor and, at the same time, tell the robot that the
   conveyor is jammed. If the motor current does not drop below 1 A within
   10 seconds, report that the conveyor jam was not cleared.

3. Robot waits for the conveyor: When the robot is told that the conveyor is jammed,
   reduce the robot speed override to 10 %. As soon as the conveyor runs normally
   again, with a motor current between 1 A and 8 A, set the speed override back to
   100 %. If the conveyor does not run again within five minutes, ask the operator
   whether to stop the robot motion, and stop it if confirmed.

4. Robot axis overheating: Check the motor temperature of the robot axis every
   minute. Above 80 °C, reduce the robot speed override to 50 %. Above 90 °C, also
   ask the operator whether to stop the robot motion and stop it if confirmed; without
   an answer within one minute, continue without stopping.
