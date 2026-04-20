# never have both cells holding the token at the same time
AG (C0_1.holdToken=0 + C0_2.holdToken=0);

# mutual exclusion
# actually need a formula for each pair of procs...
AG !(P3.procState=lock * P2.procState=lock +
     P5.procState=lock * P6.procState=lock +
     P0.procState=lock * P4.procState=lock);

# ensures liveness - needs fairness on the procs to pass
AG (P3.procState=request -> AF P3.procState=lock);

# If the antecedent is true, Left is being served; hence, it will become
# processed.  Requires fairness.
AG (C0_0.processedRight=1 * C0_0.processedLeft=0 * C0_0.prevLeft=1 ->
    AF (C0_0.processedLeft=1 * C0_0.holdToken=1));

# A weakened version of the property above.  If one only weakens the
# antecedent, or only the consequent, the property fails.
AG (C0_0.processedRight=1 * C0_0.prevLeft=1 -> AF C0_0.processedLeft=1);
