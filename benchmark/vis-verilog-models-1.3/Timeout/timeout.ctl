# The alarm will inevitably ring.
#FAIL: If no timeout is set, the alarm will not ring.
AF ring=1;

# If the timeout is set, the alarm will ring.
#FAIL: If the process terminates before the timeout,
#      the alarm will be reset.
AG(timeToAlarm[0]=1 -> AF ring=1);

#PASS: If the timeout is set and the process takes at least
#      as long as the timeout, the alarm will ring.
AG(state=COMPUTING * timeToAlarm[3:0]==remainingCpuTime[3:0] -> AF ring=1);

#PASS: Another way to say that a true timeout will cause an alarm.
AG(state=START -> AX(earlyTermination=1 -> AF ring=1));

#PASS: In the finish state all CPU time has been spent.
AG(state=FINISH -> remainingCpuTime[3:0]=0);

#PASS: The remaining CPU time can change from 0 only in the START state.
AG(state=START + (remainingCpuTime[3:0]=0 -> AX remainingCpuTime[3:0]=0));

#PASS: The FINISH state is always reached.
AF state=FINISH;

#PASS: In the end, the accumulated CPU time equals the minimum of the time
#      required to complete the process and the timeout value.
AG(state=FINISH ->
   ((earlyTermination=1 * realCpuTime[3:0]==saveTimeOut[3:0]) +
    (earlyTermination=0 * realCpuTime[3:0]==saveCpuTime[3:0])));

#PASS: No time left when in the final state.
AG(state=FINISH -> remainingCpuTime[3:0]=0);

#PASS: the alarm rings when the timeout expires.
AG(ring=1 -> timeToAlarm[3:0]=0);
