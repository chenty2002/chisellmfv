// Model of a cordic algorithm derived from the "Verilog Free-CORDIC Core"
// of the Free IP Project (http://www.free-ip.com).
// Changes made to use this model as input to vl2mv and vis.
//
// Modified by Fabio Somenzi <Fabio@Colorado.EDU>

//----------------------------------------------------------------------------
//----------------------------------------------------------------------------
//--  The Free IP Project
//--  Verilog Free-CORDIC Core
//--  (c) 2000, The Free IP Project and Rohit Sharma (srohit@free-ip.com)
//--
//--
//--  FREE IP GENERAL PUBLIC LICENSE
//--  TERMS AND CONDITIONS FOR USE, COPYING, DISTRIBUTION, AND MODIFICATION
//--
//--  1.  You may copy and distribute verbatim copies of this core, as long
//--      as this file, and the other associated files, remain intact and
//--      unmodified.  Modifications are outlined below.
//--  2.  You may use this core in any way, be it academic, commercial, or
//--      military.  Modified or not.
//--  3.  Distribution of this core must be free of charge.  Charging is
//--      allowed only for value added services.  Value added services
//--      would include copying fees, modifications, customizations, and
//--      inclusion in other products.
//--  4.  If a modified source code is distributed, the original unmodified
//--      source code must also be included (or a link to the Free IP web
//--      site).  In the modified source code there must be clear
//--      identification of the modified version.
//--  5.  Visit the Free IP web site for additional information.
//--      http://www.free-ip.com
//--
//----------------------------------------------------------------------------
//----------------------------------------------------------------------------
`define REG_MSB 15 //Actual register size is REG_MSB+1.

module cordic(CosX,SinX,theta,Sign,clock,reset);
    output [`REG_MSB+1:0] CosX,SinX;
    input [`REG_MSB:0] 	  theta;
    input 		  Sign,clock,reset;

    reg 		  AngleCin, Xsign, Ysign;
    reg [`REG_MSB:0] 	  X, Y, Angle;
    reg [3:0] 		  iteration;

    wire [`REG_MSB:0] 	  tanangle;
    wire [`REG_MSB:0] 	  BS1,BS2;
    wire [`REG_MSB:0] 	  SumX,SumY,SumAngle;
    wire 		  CarryX,CarryY,AngleCout;

    function [`REG_MSB:0] tan;
	input [3:0] index;
	begin: _tan
	    case (index)
                      //tanInverseX            |X             |tanInverseX
                      //=======================|==============|===========
          4'b0000: tan = 16'b00101101_00000000 ; //  1.000000 |45.000000
          4'b0001: tan = 16'b00011010_11111111 ; //  0.500000 |26.565051
          4'b0010: tan = 16'b00001110_00001111 ; //  0.250000 |14.036243
          4'b0011: tan = 16'b00000111_00111111 ; //  0.125000 |7.125016
          4'b0100: tan = 16'b00000011_11111111 ; //  0.062500 |3.576334
	  4'b0101: tan = 16'b00000001_11111111 ; //  0.031250 |1.789911
          4'b0110: tan = 16'b00000000_11111111 ; //  0.015625 |0.895174
          4'b0111: tan = 16'b00000000_01111111 ; //  0.007812 |0.447614
          4'b1000: tan = 16'b00000000_00111111 ; //  0.003906 |0.223811
          4'b1001: tan = 16'b00000000_00011111 ; //  0.001953 |0.111906
          4'b1010: tan = 16'b00000000_00001111 ; //  0.000977 |0.055953
          4'b1011: tan = 16'b00000000_00000111 ; //  0.000488 |0.027976
          4'b1100: tan = 16'b00000000_00000011 ; //  0.000244 |0.013988
          4'b1101: tan = 16'b00000000_00000001 ; //  0.000122 |0.006994
          4'b1110: tan = 16'b00000000_00000000 ; //  0.000061 |0.003497
          4'b1111: tan = 16'b00000000_00000000 ; //  0.000031 |0.001749

	    endcase
	end
    endfunction // tan

    initial begin
	iteration = 0;
	Angle = theta;
	X = 16'b1001101110000000;     //0.6072
	Y = 16'b0000000000000000;
	Xsign = 0;
	Ysign = 0;
	AngleCin = Sign;
    end

    assign tanangle = tan(iteration);

    /*********************Data Path******************************************/

    shifter SH1(BS1,Y,iteration); 
    Adder AddX(SumX,CarryX,Xsign,X,BS1,~AngleCin);  
    shifter SH2(BS2,X,iteration);
    Adder AddY(SumY,CarryY,Ysign,Y,BS2,AngleCin);
    Adder Add0(SumAngle,AngleCout,AngleCin,Angle,tanangle,~AngleCin);
    assign CosX={CarryX,SumX};
    assign SinX={CarryY,SumY};

    /*********************System FSM******************************************/

    always @ (posedge clock)
      if (reset) begin
	  iteration = 0;
	  Angle = theta ;
	  X = 16'b1001101110000000;     //0.6072
	  Y = 16'b0000000000000000;
	  Xsign = 0;
	  Ysign = 0;
	  AngleCin = Sign ;
      end else if (iteration != 15) begin 
	  iteration = iteration + 1;
	  Angle = SumAngle;
	  X = SumX;
	  Y = SumY;
	  Xsign = CarryX;
	  Ysign = CarryY;
	  AngleCin = AngleCout;
      end

endmodule // cordic 


module shifter(dataout,datain,shift);
    output [`REG_MSB:0] dataout;
    input [`REG_MSB:0]  datain;
    input [3:0] 	shift;

    assign dataout = shift==0  ? datain :
		     shift==1  ? { 1'b0,datain[`REG_MSB:1]}  :
		     shift==2  ? { 2'b0,datain[`REG_MSB:2]}  :
		     shift==3  ? { 3'b0,datain[`REG_MSB:3]}  :
		     shift==4  ? { 4'b0,datain[`REG_MSB:4]}  :
		     shift==5  ? { 5'b0,datain[`REG_MSB:5]}  :
		     shift==6  ? { 6'b0,datain[`REG_MSB:6]}  :
		     shift==7  ? { 7'b0,datain[`REG_MSB:7]}  :
		     shift==8  ? { 8'b0,datain[`REG_MSB:8]}  :
		     shift==9  ? { 9'b0,datain[`REG_MSB:9]}  :
		     shift==10 ? {10'b0,datain[`REG_MSB:10]} :
		     shift==11 ? {11'b0,datain[`REG_MSB:11]} :
		     shift==12 ? {12'b0,datain[`REG_MSB:12]} :
		     shift==13 ? {13'b0,datain[`REG_MSB:13]} :
		     shift==14 ? {14'b0,datain[`REG_MSB:14]} :
		                 {15'b0,datain[`REG_MSB:15]} ;

endmodule // shifter


module Adder (S, sign, Asign, A, B, AS);
    output [`REG_MSB:0] S;
    output 		sign;
    input [`REG_MSB:0] 	A, B;
    input 		Asign, AS;

    wire [`REG_MSB:0] 	Atemp, Btemp, Btemp1, Stemp;
    wire 		Y_1, Y_2, Y_3, Y_4;

    assign Y_1 = (~AS) & Asign;

    BusMux2_1 MUX_0 (Atemp,A,B,Y_1); // xchange A & B
    BusMux2_1 MUX_1 (Btemp,B,A,Y_1);

    assign Y_2 = Asign ^ AS;

    assign Btemp1 = Y_2 ? ~Btemp : Btemp;

    RCA Add (Stemp,Y_3,Atemp,Btemp1,Y_2); // Addition

    assign Y_4 = (~Y_3) & (Asign ^ AS);

    complement Compl (S,Stemp,Y_4);  // 2's Complement if result neg.

    assign sign = (~Y_3 & AS) | (~Y_3 & Asign) | (AS & Asign);

endmodule // Adder


module RCA (S,Cout,A,B,Cin);
    output [`REG_MSB:0] S;
    output 		Cout;
    input [`REG_MSB:0] 	A, B;
    input 		Cin;

    assign {Cout,S} = A + B + Cin;

endmodule //PrllAdder


module complement (dataout, datain, enable);
    output [`REG_MSB:0] dataout;
    input [`REG_MSB:0] 	datain;
    input 		enable;

    wire 		Cin;
    wire [`REG_MSB:0] 	Co;
    wire [`REG_MSB:0] 	A;

    assign A = enable ? ~datain : datain;

    assign Co[0] = A[0] & enable;
    assign Co[`REG_MSB:1] = A[`REG_MSB:1] & Co[`REG_MSB-1:0];
    assign dataout[0] = A[0] ^ enable;
    assign dataout[`REG_MSB:1] = A[`REG_MSB:1] ^ Co[`REG_MSB-1:0];

endmodule // complement


module BitMux2_1 (out,a,b,select);
    output out;
    input  a, b, select;

    assign out = select ? b : a;

endmodule //BitMux2_1


module BusMux2_1(out,data0,data1,select);
    output [`REG_MSB:0] out;
    input [`REG_MSB:0] 	data0, data1;
    input 		select;

    assign out = select ? data1 : data0;

endmodule // BusMux2_1
