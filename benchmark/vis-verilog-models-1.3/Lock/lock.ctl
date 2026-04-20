#PASS: It is always possible to open the lock.
AG(EF(open=1));

#PASS: If the lock is closed it may remain so indefinitely.
AG(open=0 * !(state[1:0]=2 * position[4:0]=15 * upReg=1) -> EG(open=0));

#PASS: Transition to State 3 only occurs under the required circumstances.
AG(state[1:0]=3 +
   !E(!(state[1:0]=2 * position[4:0]=15 * upReg=1) U state[1:0]=3));

#PASS: Transition to State 2 only occurs under the required circumstances.
AG(state[1:0]=2 +
   !E(!(state[1:0]=1 * position[4:0]=21 * downReg=1) U state[1:0]=2));

#PASS: Transition to State 1 only occurs under the required circumstances.
AG(state[1:0]=1 +
   !E(!(state[1:0]=0 * position[4:0]=12 * upReg=1) U state[1:0]=1));

#PASS: To get from State 2 to State 0 either downReg=1 or State 3 must occur.
AG(state[1:0]=2 -> !E(downReg=0 * !state[1:0]=3 U state[1:0]=0));

#PASS: To get from State 1 to State 0 either upReg=1 or State 2 must occur.
AG(state[1:0]=1 -> !E(upReg=0 * !state[1:0]=2 U state[1:0]=0));

#  These formulae require the fairness condition upReg=1 + downReg=1.

#PASS: From State 0 either downReg=1 or State 1 occurs.
AG(state[1:0]=0 -> AF(downReg=1 + state[1:0]=1));

#PASS: The right combination will open the lock.
AG(AF(open=1 + state[1:0]=0 * downReg=1 + state[1:0]=1 * upReg=1 +
      state[1:0]=2 * downReg=1));
