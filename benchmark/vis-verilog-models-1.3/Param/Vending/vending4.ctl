\define totalGE7	(v.total[3:0]=7 + v.total[3]=1)
\define totalGE5	(v.total[3]=1 +
			 v.total[2]=1 * (v.total[1]=1 + v.total[0]=1))
\define totalLE9	(v.total[3]=0 + v.total[2:1]=0)
\define balanceLE9	(balance[4]=1 + balance[3]=0 + balance[2:1]=0)
# The following definitions depend on the value of BITS.
\define fullT5		(v.t5[3:0]=15)
\define fullT10		(v.t10[3:0]=15)
\define fullT25		(v.t25[3:0]=15)
\define emptyT5		(v.t5[3:0]=0)
\define emptyT10	(v.t10[3:0]=0)
\define emptyT25	(v.t25[3:0]=0)

#PASS: The total counters do not overflow.
#AG((\fullT5  -> AX !\emptyT5)  * (\emptyT5  -> AX !\fullT5)  *
#   (\fullT10 -> AX !\emptyT10) * (\emptyT10 -> AX !\fullT10) *
#   (\fullT25 -> AX !\emptyT25) * (\emptyT25 -> AX !\fullT25));

#PASS: The balance is never negative and never reaches 75c.
#AG(balance[4]=0 * !balance[4:0]=15);

#PASS: Unless one of the counters saturates, the balance is 0 infinitely often.
AG AF(balance[4:0]=0 + \fullT5 + \fullT10 + \fullT25);

#PASS: No more than 45c can be deposited during a transaction.
#AG \totalLE9;

#PASS: If total ever exceeds 30c, a beverage will be released.
#AG(\totalGE7 -> AF v.state=BEVERAGE);

#PASS: In state CHANGE we have at least 25c from the current transaction.
#AG(v.state=CHANGE -> \totalGE5);

#PASS: If in the CHANGE state the total is not 30c or there is at least
#      one nickel, then a beverage will be released.
#AG((v.state=CHANGE * !(v.total[3:0]=6 * \emptyT5)) -> AF v.state=BEVERAGE);

#PASS: If in the CHANGE state the total is 35c or more, then either a
#      nickel or a dime has been deposited in the current transaction.
#AG((v.state=CHANGE * \totalGE7) -> !(v.l5[2:0]=0 * v.l10[1:0]=0));

#PASS: In the REFUND state we have no quarters from this transaction,
#      and no nickels at all.
#AG(v.state=REFUND -> (v.l25[0]=0 * v.l5[2:0]=0 * \emptyT5));

#PASS: On entry to REFUND, we have exactly three dimes from this transaction.
#AG(!v.state=REFUND -> AX(!v.state=REFUND + v.l10[1:0]=3));

#PASS: In the BEVERAGE state we have exactly 25c from this transaction.
#      However, total is not up to date if we borrowed a nickel from the
#      total count to give change out of three dimes.  Hence, total may
#      read either 25c or 30c.
#AG(v.state=BEVERAGE -> (v.total[3:2]=1 * (v.total[1:0]=1 + v.total[1:0]=2)));

#PASS: On entry to ACCEPTING, we have no money from this transaction.
#AG(!v.state=ACCEPTING -> AX(!(v.state=ACCEPTING) + v.total[3:0]=0));

#FAIL: The balance never exceeds 45c.  This fails because there is a delay
#      in giving change.
#AG \balanceLE9;

#FAIL: if coins keep being deposited, it is inevitable for the nickel
#      reservoir to fill up.  (Fails in spite of fairness condition because
#      one of the total counters may saturate, or refunds may continue
#      to take place.)
#AG AF \fullT5;

#FAIL: if coins keep being deposited, every transaction will terminate
#      with the release of a beverage, or with a refund.  (Fails in spite
#      of fairness condition because one of the total counters may saturate.)
#AG(v.state=ACCEPTING -> AF(v.state=REFUND + v.state=BEVERAGE));
