AG(I_WWorkIFU=1 -> I_DataDep=0 * I_EExceptMAU[3]=0 + I_EExceptIDU[3]=1 + I_EExceptEXU[3]=1);

EF (I_DataDep=0 * I_EExceptMAU[3]=0 + I_EExceptIDU[3]=1 + I_EExceptEXU[3]=1);

AG(I_WWorkIDU=1 -> I_DataDep=1 + I_nIFUNotReady=1  * R_WorkIFU=1);

EF (I_DataDep=1 + I_nIFUNotReady=1  * R_WorkIFU=1);

AF(I_WWorkEXU=1 -> AF(I_nFlushPipe=1 * I_DataDep=0 * R_WorkIDU=1));

AG(I_WWorkMAU =1 -> I_nFlushPipe=1 * R_WorkEXU=1);

AG(I_WWorkWB=1 ->  R_WorkMAU=1);
	                      
AF(I_SStepIDU =1 ->  I_DataDep=0 * I_nIFUNotReady=1  *  R_WorkIFU=1);

AG(I_SStepEXU=1 -> I_DataDep=0 *  R_StepIDU=1);

AG(I_SStepMAU=1 -> AG (R_StepEXU=1));

AG(I_SStepWB=1 -> AG (R_StepMAU=1));

A(I_nMAUNotReady =1 * (MultiplyReg[4]=1 + I_EExceptEXU[3]=1) U MultiplyReg[4:0] = 0);

AG(I_nMAUNotReady=1 * R_EXUMultiply=1  * MultiplyReg[4]=0 -> EF (R_ExceptIDU[3:0] = 1  -> I_EExceptIDU[3:0]=1) *  EF (R_ExceptEXU[3:0] = 1 -> I_EExceptEXU[3:0]=1));

AG(R_EXUMultiply=1 * MultiplyReg[4] = 0 -> AG(R_StepMAU=1 -> I_SStepMAU=1));

AG(R_EXUMultiply=0 * MultiplyReg[4] = 1 -> AG(R_WorkIFU=1 -> I_WWorkIFU=1 + I_DataDep=1));

AG(R_EXUMultiply=0 * MultiplyReg[4] = 1 -> AG(R_StepIDU=1 -> I_SStepIDU=1 + I_DataDep=1));

EF(R_EXUMultiply=1 -> I_EXUMultiply=1 * I_DataDep=0);
