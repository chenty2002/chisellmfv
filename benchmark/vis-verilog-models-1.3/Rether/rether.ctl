# Properties for the two rether models (RTF and SQO).  Differences in
# outcome between the two are noted below.  This set of properties consists
# of the 14 properties in the Du et al. paper, followed by other properties
# of interest.  The 14 properties were derived from their English descriptions
# in the paper, rather than from the mu-calculus.


# Definition of useful predicates.

# A real-time transmission occurs.
\define SRT	rt<*0*>=1 + rt<*1*>=1 + rt<*2*>=1 + rt<*3*>=1
# A non-real-time transmission occurs.
\define SNRT	nrt<*0*>=1 + nrt<*1*>=1 + nrt<*2*>=1 + nrt<*3*>=1
# A transmission of either type occurs.
\define S0	\SRT + \SNRT
# This variant of the previous predicate is used to tighten the
# original T3T property.  Since the "admitted" nodes are always polled
# in ID order, if Node 0 performs an RT transmission, then it is the
# first transmission of a token rotation cycle.  Hence, the second
# transmission can be anything but an RT transmission by Node 0.
\define SM0	rt<*1*>=1 + rt<*2*>=1 + rt<*3*>=1 + \SNRT
# These three predicates are used to tighten T3T for the SQO policy.
\define S1	 rt<*0*>=1 +  rt<*1*>=1 +  rt<*2*>=1 +
		nrt<*0*>=1 + nrt<*1*>=1 + nrt<*2*>=1
\define S2	 rt<*1*>=1 +  rt<*2*>=1 +  rt<*3*>=1 +
		nrt<*0*>=1 + nrt<*1*>=1 + nrt<*2*>=1
\define S3	 rt<*1*>=1 +  rt<*2*>=1 +  rt<*3*>=1 +
		nrt<*1*>=1 + nrt<*2*>=1 + nrt<*3*>=1


# Properties from Du, Smolka, and Cleaveland.

#PASS: DLF: Deadlock freedom.
AG EX TRUE;

#PASS: CC: Token rotation cycle completion.
AG(start=1 -> AX(A(start=0 U cycle=1)));

#PASS: RT0-3: Bandwidth guarantee for RT nodes.
AG(res<*0*>=1 -> !(EG(rt<*0*>=0) +
                   E((cycle=1 -> EX(E(rt<*0*>=0 U cycle=1))) U rt<*0*>=1)));
AG(res<*1*>=1 -> !(EG(rt<*1*>=0) +
                   E((cycle=1 -> EX(E(rt<*1*>=0 U cycle=1))) U rt<*1*>=1)));
AG(res<*2*>=1 -> !(EG(rt<*2*>=0) +
                   E((cycle=1 -> EX(E(rt<*2*>=0 U cycle=1))) U rt<*2*>=1)));
AG(res<*3*>=1 -> !(EG(rt<*3*>=0) +
                   E((cycle=1 -> EX(E(rt<*3*>=0 U cycle=1))) U rt<*3*>=1)));
# These properties can also be written thus, but then there are some
# vacuous passes due to the fact that rt<*i*>=1 implies cycle=0.
#AG(res<*0*>=1 -> A(rt<*0*>=0 U (cycle=1 * AX(A(cycle=0 U rt<*0*>=1)))));
#AG(res<*1*>=1 -> A(rt<*1*>=0 U (cycle=1 * AX(A(cycle=0 U rt<*1*>=1)))));
#AG(res<*2*>=1 -> A(rt<*2*>=0 U (cycle=1 * AX(A(cycle=0 U rt<*2*>=1)))));
#AG(res<*3*>=1 -> A(rt<*3*>=0 U (cycle=1 * AX(A(cycle=0 U rt<*3*>=1)))));

#PASS: NS0-3: No starvation for NRT traffic.
AG AF nrt<*0*>=1;
AG AF nrt<*1*>=1;
AG AF nrt<*2*>=1;
AG AF nrt<*3*>=1;

#PASS: RTT: At least one RT data transmission in each cycle.
AG(start=1 -> !E(!\SRT U cycle=1));

#PASS: NRT: At least one NRT data transmission in each cycle.
AG(start=1 -> !E(!\SNRT U cycle=1));

#PASS: T3T: A total of three data transmissions in each cycle.  This property
# has a number of vacuous passes with either policy.  See below for
# strengthened properties that pass non vacuously for one policy, but fail
# for the other.
AG(start=1 -> !E(!\S0 U EX(E(!\SM0 U EX(E(!\S0 U cycle=1))))));

#PASS: RTF: RT-first property.
AG(\SNRT -> !E(cycle=0 U \SRT));


# Supplemental properties.

#PASS: Start and cycle are mutually exclusive.
AG(start=0 + cycle=0);

#PASS: Mutual exclusion.
AG ((!\SRT + !\SNRT) *
    (rt<*0*>=0 + rt<*1*>=0) * (rt<*0*>=0 + rt<*2*>=0) *
    (rt<*0*>=0 + rt<*3*>=0) * (rt<*1*>=0 + rt<*2*>=0) *
    (rt<*1*>=0 + rt<*3*>=0) * (rt<*2*>=0 + rt<*3*>=0) *
    (nrt<*0*>=0 + nrt<*1*>=0) * (nrt<*0*>=0 + nrt<*2*>=0) *
    (nrt<*0*>=0 + nrt<*3*>=0) * (nrt<*1*>=0 + nrt<*2*>=0) *
    (nrt<*1*>=0 + nrt<*3*>=0) * (nrt<*2*>=0 + nrt<*3*>=0));

#PASS: New cycles start and end infinitely often.  The second is actually
# redundant, since we have proved that cycle=1 follows inevitably start=1.
AG AF start=1;
AG AF cycle=1;

#PASS: At least one RT allocation.
AG !RT_count[1:0]=0;
AG (node<*0*>=1 + node<*1*>=1 + node<*2*>=1 + node<*3*>=1);

#PASS: At most one reservation per cycle for each node.
AG!(res<*0*>=1 * EX E(cycle=0 U res<*0*>=1));
AG!(res<*1*>=1 * EX E(cycle=0 U res<*1*>=1));
AG!(res<*2*>=1 * EX E(cycle=0 U res<*2*>=1));
AG!(res<*3*>=1 * EX E(cycle=0 U res<*3*>=1));

#PASS: All nodes with reserved bandwidth at the begginning of the cycle
# perform their RT transmission during the cycle.
AG(start=1 * node<*0*>=1 -> !E(rt<*0*>=0 U cycle=1));
AG(start=1 * node<*1*>=1 -> !E(rt<*1*>=0 U cycle=1));
AG(start=1 * node<*2*>=1 -> !E(rt<*2*>=0 U cycle=1));
AG(start=1 * node<*3*>=1 -> !E(rt<*3*>=0 U cycle=1));

#PASS: All nodes may allocate bandwidth.
AG EF node<*0*>=1;
AG EF node<*1*>=1;
AG EF node<*2*>=1;
AG EF node<*3*>=1;

#PASS: All nodes may deallocate bandwidth.
AG EF node<*0*>=0;
AG EF node<*1*>=0;
AG EF node<*2*>=0;
AG EF node<*3*>=0;

#PASS: RT requests are served in ID order.
AG!(rt<*1*>=1 * E(cycle=0 U rt<*0*>=1));
AG!(rt<*2*>=1 * E(cycle=0 U rt<*1*>=1));
AG!(rt<*3*>=1 * E(cycle=0 U rt<*2*>=1));

#PASS: This stronger version of the T3T property holds for the RTF policy.
# It says that in each cycle there are exactly three transmissions: The first
# is always an RT transmission; the last is always an NRT transmission; and
# the middle one can be anything but an RT transmission by Node 0.
AG(start=1 -> !E(!\SRT U EX(E(!\SM0 U EX(E(!\SNRT U cycle=1))))));

#PASS: This stronger version of the T3T property holds for the SQO policy.
# It says that in each cycle there are exactly three transmissions: The first
# cannot be by Node 3; the second cannot be rt<*0*> or nrt<*3*>; and the
# the third cannot be by Node 0.
AG(start=1 -> !E(!\S1 U EX(E(!\S2 U EX(E(!\S3 U cycle=1))))));

#PASS: Only for the SQO policy.  In the SQO policy the token is given to
# each node in ID order so that res<*3*>=1 can only happen on the clock
# cycle immediately preceding the end of the token rotation cycle.
AG(res<*3*>=1 -> AX cycle=1);
