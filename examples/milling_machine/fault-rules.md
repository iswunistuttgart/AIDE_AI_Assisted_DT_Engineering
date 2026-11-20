# Fault rules of the 5-axis milling machine

1. Worn tool: If the remaining life of the tool in the spindle drops below 10 %, ask
   the operator whether to change to the sister tool number 17, and change it if
   confirmed. If the operator declines or does not answer within two minutes, report
   that a worn tool is still in use.

2. Chatter: If the spindle vibration stays above 4 mm/s for 5 seconds, set the feed
   override according to the vibration: 40 % above 8 mm/s, 60 % above 6 mm/s, and
   80 % otherwise. If the vibration does not drop below 3 mm/s within one minute,
   stop the machine safely and report the unresolved chatter.

3. Rotary table overheating: If the motor temperature of the rotary table rises above
   70 °C, reduce the feed override to 50 % and, at the same time, switch on the
   coolant boost. If the temperature does not drop below 60 °C within two minutes,
   stop the machine safely and report the overheating.

4. Coolant loss: If the coolant pressure stays below 2 bar for 10 seconds, stop the
   machine safely and report the coolant loss. If the safe stop is not finished within
   five minutes, report that the operator did not respond.

Stopping the machine safely means: activate the feed hold, then ask the operator
whether to abort the NC program, and abort it if confirmed.
