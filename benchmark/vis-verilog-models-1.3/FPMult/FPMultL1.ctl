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
