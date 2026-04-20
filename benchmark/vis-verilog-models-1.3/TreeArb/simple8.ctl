#PASS: Mutual exclusion.
AG !(
  (P0.state=locking + P1.state=locking + P2.state=locking + P3.state=locking) *
  (P4.state=locking + P5.state=locking + P6.state=locking + P7.state=locking) +
  (P0.state=locking + P1.state=locking + P4.state=locking + P5.state=locking) *
  (P2.state=locking + P3.state=locking + P6.state=locking + P7.state=locking) +
  (P0.state=locking + P2.state=locking + P4.state=locking + P6.state=locking) *
  (P1.state=locking + P3.state=locking + P5.state=locking + P7.state=locking));

#PASS: Absence of starvation.  Needs fairness constraints.
AG (P0.state=requesting -> AF P0.state=locking);
AG (P1.state=requesting -> AF P1.state=locking);
AG (P2.state=requesting -> AF P2.state=locking);
AG (P3.state=requesting -> AF P3.state=locking);
AG (P4.state=requesting -> AF P4.state=locking);
AG (P5.state=requesting -> AF P5.state=locking);
AG (P6.state=requesting -> AF P6.state=locking);
AG (P7.state=requesting -> AF P7.state=locking);
