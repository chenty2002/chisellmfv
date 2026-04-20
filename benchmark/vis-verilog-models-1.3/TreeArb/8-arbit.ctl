# never have both cells holding the token at the same time
AG ( ! ( ( G1.C1.holdToken = myTRUE ) * ( G1.C2.holdToken = myTRUE) ) );

# never have G1.P3 and G1.P2 holding the token;
# actually need a formula for each pair of procs...
AG ( ! ( ( G1.P3.procState = lock ) * ( G1.P2.procState = lock) ) );

# ensures liveness - needs fairness on the procs to pass
AG ( ( G1.P3.procState = request ) -> AF ( G1.P3.procState = lock) );
