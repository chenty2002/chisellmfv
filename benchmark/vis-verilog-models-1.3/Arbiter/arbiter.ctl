# safety: mutual exclusion
AG ( !(ackA = 1 * ackB = 1 + ackB = 1 * ackC = 1 + ackC = 1 * ackA=1) );

# liveness: 
AG( (reqA = 1) -> AF(ackA = 1) );
AG( (reqB = 1) -> AF(ackB = 1) );
AG( (reqC = 1) -> AF(ackC = 1) );
