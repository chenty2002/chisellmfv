# Define common subformulae.
\define START	(start=1 * FPM.state[1:0]=0)
\define Validx	(x[6:3]=0 -> x[2:0]=0)
\define Validy	(y[6:3]=0 -> y[2:0]=0)
\define Validz	(z[6:3]=0 -> z[2:0]=0)
\define NaNx	(x[6:3]=b1111 * !(x[2:0]=0))
\define NaNy	(y[6:3]=b1111 * !(y[2:0]=0))
\define NaNz	(z[6:3]=b1111 * !(z[2:0]=0))
\define Infx	x[6:0]=b1111000
\define Infy	y[6:0]=b1111000
\define Infz	z[6:0]=b1111000

#PASS: Legal operands cannot produce illegal results. The only illegal
# operands in our case are the denormals.

AG((\START * \Validy * \Validx) -> AX:3(\Validz));

#FAIL: If the sign bits are different the result is negative.
# This formula fails because one operand may be NaN.

AG((\START * !(y[7]==x[7])) -> AX:3(z[7]=1));

#PASS: If the sign bits are the same the result is positive.

AG((\START * y[7]==x[7]) -> AX:3(z[7]=0));

#PASS: If one of the operands is NaN the result is NaN.

AG((\START * (\NaNy + \NaNx)) -> AX:3(\NaNz));

#FAIL: If one of the operands is zero and the other operand is
#      not NaN the result is zero.
# These properties should fail, because infinity * zero = NaN.

AG((\START * y[6:0]=0 * !\NaNx) -> AX:3(z[6:0]=0));
AG((\START * x[6:0]=0 * !\NaNy) -> AX:3(z[6:0]=0));

#PASS: If one of the operands is infinite and the other operand is
#      neither NaN nor zero the result is infinite.

AG((\START * \Infy * !\NaNx * !(x[6:3]=0)) -> AX:3(\Infz));
AG((\START * \Infx * !\NaNy * !(y[6:3]=0)) -> AX:3(\Infz));
