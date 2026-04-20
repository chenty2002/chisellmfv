#Resetn is always reached globally
AG(EF Resetn= 0);

#It is possible that every time LStep_WB is true implies that 
#every time other condition is true.
EF((AG LStep_WB = 1) -> ( AG (WBp_RPCC ==MAUp_RPCC * WBp_Cond ==
MAU_Cond * WBp_add[5:0] == MAU_Dest[5:0] * WBp_Data[31:27] ==
MAU_Data[31:27]))); 

#Globally when RegA[5:0] is true and others then SelA is 3
AG((RegA[5:0] = 1 * RegA[5]=1 * EXUp_data_source
=0 * EXU_Cond=1 * LWork_EXU=1 * RegA[4:0] =30) -> AG(SEL_A[1:0]=3));

#Globally condition
AG((RegA[5:0] = 1 * RegA[5]=1 * MAU_Cond =1
* LWork_MAU=1 * RegA[4:0] =30) -> AG(SEL_A[1:0]=2));

AG(LStep_MAU=1) -> AG(MAUp_RPCC== EXUp_RPCC);

AG(RegA[4:0]=31) -> AG (dOpd1[31:26]=0);

AG((RegB[5:0]=1 * RegB[5]=1 * EXUp_data_source=0 * EXU_Cond=1 * LWork_EXU=1 *
RegB[4:0] = 30) -> AG(SEL_B[1:0]=3));

AG((RegA[5:0]=1 * RegB[5:0]=1 * EXUp_data_source=1 * LWork_EXU=1 *
RegA[4:0]=30) + (RegA[5:0]=1 * RegB[5:0]=1 * EXUp_data_source=1 *
LWork_EXU=1 * RegB[4:0]=30)) -> AG(dWait_for_data = 1);

EF(RegA[4:0]=31 * dOpd1[31:26]=0); 

EF(RegB[4:0]=31 * dOpd1[31:26]=0);

EF(SEL_A[1:0]=2 -> dOpd1[31:26]==EXU_ResData[31:26]);
