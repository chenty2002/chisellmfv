#FAIL: There is no way to safely move all three passengers to the right side.
# The counterexample to this property provides an optimal strategy to do so.
!E(safe=1 U final=1);

#PASS: From any reachable safe state it is possible to complete successfully.
AG(safe=1 -> E(safe=1 U final=1));

#PASS: It is possible to procrastinate completion indefinitely.
EG(safe=1 * final=0);

#FAIL: In the initial state the boat is on the left; since all passengers
# are also on the left, the boat will move to the right no matter what
# passenger is selected.
AG(EX(boat=LEFT) * EX(boat=RIGHT));
