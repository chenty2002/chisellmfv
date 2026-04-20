module nullModem(clock,reset,load, reset, ok);
    input       clock;		// active edge is positive one
    input       reset;		// active low
    input       load;
    input [7:0] dataIn;
    output 	ok;		// transfer is correct


    wire [7:0] 	parallelOut, parallelIn;
    wire 	shiftLoad;
    wire 	enable;
    wire 	serialOut;
    wire 	txEmpty;
    wire 	serialIn;
    wire 	dataRdy;
    wire 	bitClock;

    
    reg 	rst;
    reg 	ld;

    initial begin
	rst = 1;
	ld = 0;
    end

    always @ (posedge clock) begin
	rst = reset;
	ld = load;
    end

    control ctl(clock,rst,ld,dataIn,enable,parallelOut,
		parallelIn,shiftLoad,txEmpty,dataRdy,bitClock,ok);
    UartXmt Tx(shiftLoad,enable,clock,parallelOut,rst,serialOut,txEmpty);
    UartRx  Rx(clock,rst,serialIn,dataRdy,parallelIn,bitClock);

    assign serialIn = serialOut;		// null modem


Buechi Buechi(clock,rst,ok,ld,fair,scc);
    
endmodule // nullModem

typedef enum {Init,n1,n2,n4,Trap} states;

module Buechi(clock,rst,ok,ld,fair,scc);
  input clock,rst,ok,ld;
  output fair,scc;
  states reg state;
  states wire ND_n1_n2;
  states wire ND_n1_n4;
  assign ND_n1_n2 = $ND(n1,n2);
  assign ND_n1_n4 = $ND(n1,n4);
  assign fair = (state == n1);
    assign scc = (state == n1)||(state == n4);
  initial state = Init;
  always @ (posedge clock) begin
    case (state)
      n2:
	case ({ld,ok,rst})
	3'b000: state = n2;
	3'b001: state = ND_n1_n2;
	3'b01?: state = n2;
	3'b1??: state = n2;
	endcase
      Trap:
	state = Trap;
      n1,n4:
	case ({ld,ok,rst})
	3'b0?0: state = Trap;
	3'b001: state = ND_n1_n4;
	3'b011: state = n4;
	3'b1??: state = Trap;
	endcase
      Init:
	state = n2;
    endcase
  end
endmodule

module control(clock,reset,ld,dataIn,enable,parallelOut,
	       parallelIn,shiftLoad,txEmpty,dataRdy,bitClock,ok);
    input        clock;
    input 	 reset;
    input 	 ld;
    input [7:0]  dataIn;
    output 	 enable;
    output [7:0] parallelOut;
    input [7:0]  parallelIn;
    output 	 shiftLoad;
    input 	 txEmpty;
    input 	 dataRdy;
    input 	 bitClock;
    output 	 ok;

    reg [7:0] 	 rxBuf;
    reg [7:0] 	 txBuf;
    reg 	 shiftLoad;
    reg [3:0] 	 freqDiv;

    initial begin
	rxBuf = 8'b10000000;  txBuf = 8'b00000001;
	shiftLoad = 1;
	freqDiv = 0;
    end

    always @ (posedge clock) begin
	if (reset == 0) begin
	    shiftLoad = 1;
	    freqDiv = 0;
	    if (ld == 1) begin
		txBuf = dataIn;
	    end
	end
	else begin
	    if (dataRdy == 1) begin
		rxBuf = parallelIn;
	    end // if (dataRdy == 1)

	    if (enable == 1 && txEmpty == 1) begin
		if (shiftLoad == 1) begin
		    shiftLoad = 0;
		end // if (shifLoad == 1)
		else begin
		    shiftLoad = 1;
		end // else: !if(shifLoad == 1)
	    end // if (txEmpty)
	    else if (ld == 1) begin
		txBuf = dataIn;
	    end

	    freqDiv = freqDiv + 1;

	end // else: !if(reset == 0)
    end // always @ (posedge clock)
    
    assign enable = freqDiv == 7;
    assign ok = rxBuf == txBuf;
    assign parallelOut = txBuf;

endmodule // control


//  Purpose:   Models the receive portion of a UART.
//
module UartRx(Clk16xT,ResetF,Serial_InT,DataRdyT,DataOuT,BitClkT);
    input        Clk16xT;
    input        ResetF;
    input        Serial_InT;
    output       DataRdyT;
    output [7:0] DataOuT;
    output 	 BitClkT;

    parameter 	 RxInit_c = 10'b1111111111;
    reg [9:0] 	 RxReg;		// the receive register
    reg [3:0] 	 Count16;	// to divide by 16
    reg 	 RxMT;		// receive register empty
    reg 	 RxIn;		// registered serial input

    initial begin
	RxReg = RxInit_c;
	Count16 = 0;
	RxMT = 1;
	RxIn = 0;
    end // initial begin

    always @ (posedge Clk16xT) begin
	// Reset
	if (ResetF == 0) begin
	    Count16 = 0;		// reset divide by 16 counter
	    RxMT = 1;			// new message starting
	    RxReg = RxInit_c;
	end

	// Start bit   
	else if (RxMT == 1 && RxIn == 0) begin
	    Count16 = 0;		// reset divide by 16 counter
	    RxMT = 0;			// new message starting
	    RxReg = RxInit_c;
	end

	// If in a receive transaction mode
	// if @ mid bit clock then clock data into register
	else if (Count16 == 7 && RxMT == 0) begin	// mid clock
	    RxReg[8:0] = RxReg[9:1];
	    RxReg[9] = RxIn;
	    Count16 = Count16 + 1;
	end

	// Normal counter increment modulo 16
	else begin
	    Count16 = Count16 + 1;
	end

	// Clock serial input into RxIn
	RxIn = Serial_InT;

	// Check if a data word is received
	if (DataRdyT == 1) begin 
	    RxMT = 1;
	end
    end // always @ (posedge Clk16xT)

    assign DataRdyT = RxMT == 0 && RxReg[9] == 1 && RxReg[0] == 0;

    assign BitClkT = Count16 == 9;

    assign DataOuT = RxReg[8:1];

endmodule // UartRx


//-----------------------------------------------------------------------------
//  Purpose:  Models the transmit register of a UART.
//            Operation is as follows:
//            . All operations occur on rising edge of CLK.
//            . If ResetF == 0 then
//                XmitReg is reset to 1111111111.
//                Count   is reset to 0.
//            . If ClkEnbT == 1 and Shift_LdF == 0 and ResetF == 1 then
//                {1'b1, DataT, 1'b0} gets loaded into XmitReg.
//                Count is reset to 0
//            . If ClkEnbT == 1 and Shift_LdF == 1 and ResetF == 1 then
//                {1'b1, XmitReg[9:1]} gets loaded into XmitReg
//                (shift right with a 1 shifted in)
//                Count is incremented to less than 10
//                (i.e. if it is 9, then it stays at 9)
//-----------------------------------------------------------------------------
module UartXmt(Shift_LdF,ClkEnbT,Clk,DataT,ResetF,Serial_OuT,XmitMT);
    input       Shift_LdF;
    input       ClkEnbT; 
    input       Clk;
    input [7:0] DataT;
    input 	ResetF;
    output 	Serial_OuT;	// serial output
    output 	XmitMT;		// transmitter empty

    reg [9:0] 	XmitReg;	// the transmit register
    reg [3:0] 	Count;		// # of serial bits sent
  

    initial begin
	Count = 0;
	XmitReg = 10'b1111111111;
    end // initial begin

    always @ (posedge Clk) begin
	if (ResetF == 0) begin
	    XmitReg = 10'b1111111111;
	    Count   = 9;
	end
	else if (ClkEnbT == 1 && Shift_LdF == 0 && ResetF == 1) begin
	    XmitReg[9]   = 1;		// stop bit(s)
	    XmitReg[8:1] = DataT;	// payload
	    XmitReg[0]   = 0;		// start bit
	    Count        = 0;
	end
	else if (ClkEnbT == 1 && Shift_LdF == 1 && ResetF == 1) begin
	    XmitReg[8:0] = XmitReg[9:1];
	    XmitReg[9]   = 1;
	    if (Count != 9)
	      Count = Count + 1;
	end
    end

    assign Serial_OuT = XmitReg[0];

    assign XmitMT = Count == 9;

endmodule // UartXmt
