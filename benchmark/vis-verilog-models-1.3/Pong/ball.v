
typedef enum {WaitVS, IncD, IncCoord, LoadY} BallState;

module Ball(clock, HSync, VSync, RasterCollision,
	    BitRaster, BitRasterShade, BRLftBall, BRRgtBall);
    parameter             WIDTH_VIDEO = 10;
    parameter 		  StartX16 = 20; // init ball position, divided by 16
    parameter 		  StartY16 = 15;
    parameter 		  StartX = StartX16*16;
    parameter 		  StartY = StartY16*16;
    input 		  clock, HSync, VSync, RasterCollision;
    output 		  BitRaster, BitRasterShade, BRLftBall, BRRgtBall;

    reg [WIDTH_VIDEO-1:0] BallPosX, BallPosY, BallDX, BallDY, qDX, qDY;
    BallState reg         SSMoveBall;
    reg 		  BitRaster, BitRasterShade;
    reg 		  TopBall, BotBall, LftBall, RgtBall;
    reg 		  MTopBall, MBotBall, MLftBall, MRgtBall;
    wire 		  HorzBall, VertBall, MiddleBallX, MiddleBallY;
    reg 		  CollisionTop, CollisionBot,
			  CollisionLft, CollisionRgt;
    reg 		  CollisionMTop, CollisionMBot,
			  CollisionMLft, CollisionMRgt;
    reg 		  HorzShade, VertShade;
    wire [2:0] 		  Random4to7;

    function [WIDTH_VIDEO-1:0] Max3; // limit input value to -3..-1, 1..3
	input [WIDTH_VIDEO-1:0] ValueIn;
	begin: _Max3
	    Max3[1:0] = ValueIn[1:0] || (ValueIn[1:0] == 0);
	    Max3[WIDTH_VIDEO-1:2] = {WIDTH_VIDEO-2{ValueIn[WIDTH_VIDEO-1]}};
	end
    endfunction // Max3

    initial begin
	BallPosX = 0; BallPosY = 0; BallDX = 0; BallDY = 0;
	qDX = 0; qDY = 0;
	SSMoveBall = WaitVS;
	BitRaster = 0; BitRasterShade = 0;
	TopBall = 0; BotBall = 0; LftBall = 0; RgtBall = 0;
	MTopBall = 0; MBotBall = 0; MLftBall = 0; MRgtBall = 0;
	CollisionTop = 0; CollisionBot = 0;
	CollisionLft = 0; CollisionRgt = 0;
	CollisionMTop = 0; CollisionMBot = 0;
	CollisionMLft = 0; CollisionMRgt = 0;
	HorzShade = 0; VertShade = 0;
    end

    assign Random4to7[2] = 1;
    assign Random4to7[1:0] = $ND(0,1,2,3);

    always @ (posedge clock) begin
	case (SSMoveBall)
	  WaitVS: begin
	      if (VSync) SSMoveBall = IncD;
	  end
	  IncD: begin
	      if (CollisionLft)
		BallDX = Max3(BallDX + {{WIDTH_VIDEO-3{1'b0}},Random4to7});
	      else if (CollisionRgt)
		BallDX = Max3(BallDX - {{WIDTH_VIDEO-3{1'b0}},Random4to7});
	      else
		BallDX = Max3(BallDX);
	      if (CollisionTop)
		BallDY = Max3(BallDY + {{WIDTH_VIDEO-3{1'b0}},Random4to7});
	      else if (CollisionBot)
		BallDY = Max3(BallDY - {{WIDTH_VIDEO-3{1'b0}},Random4to7});
	      else
		BallDY = Max3(BallDY);
	      SSMoveBall = IncCoord;
	  end
	  IncCoord: begin
	      if (!(CollisionLft && CollisionRgt))
		BallPosX = BallPosX + BallDX;
	      if (!(CollisionTop && CollisionBot))
		BallPosY = BallPosY + BallDY;
	      SSMoveBall = LoadY;
	  end
	  LoadY: begin
	      SSMoveBall = WaitVS;
	  end
	endcase
    end

    // Collision logic.
    always @ (posedge clock) begin
	if (SSMoveBall == LoadY) begin
	    CollisionTop = 0;
	    CollisionBot = 0;
	    CollisionLft = 0;
	    CollisionRgt = 0;
	end else if (CollisionLft && CollisionRgt &&
		     CollisionTop && CollisionBot) begin
	    CollisionTop = CollisionMTop;
	    CollisionBot = CollisionMBot;
	    CollisionLft = CollisionMLft;
	    CollisionRgt = CollisionMRgt;
	end else begin
	    CollisionTop = CollisionTop || (TopBall && RasterCollision);
	    CollisionBot = CollisionBot || (BotBall && RasterCollision);
	    CollisionLft = CollisionLft || (LftBall && RasterCollision);
	    CollisionRgt = CollisionRgt || (RgtBall && RasterCollision);
	end
    end

    always @ (posedge clock) begin
	if (SSMoveBall == LoadY) begin
	    CollisionMTop = 0;
	    CollisionMBot = 0;
	    CollisionMLft = 0;
	    CollisionMRgt = 0;
	end else begin
	    CollisionMTop = CollisionMTop || (MTopBall && RasterCollision);
	    CollisionMBot = CollisionMBot || (MBotBall && RasterCollision);
	    CollisionMLft = CollisionMLft || (MLftBall && RasterCollision);
	    CollisionMRgt = CollisionMRgt || (MRgtBall && RasterCollision);
	end
    end

    // Detection of ball edges for collision logic.
    // Ball interior.
    assign HorzBall = (qDX[WIDTH_VIDEO-1:4] == {WIDTH_VIDEO{1'b0}}-1-StartX16);
    assign VertBall = (qDY[WIDTH_VIDEO-1:4] == {WIDTH_VIDEO{1'b0}}-1-StartY16);
    // Middle sides.
    assign MiddleBallX = (qDY == {WIDTH_VIDEO{1'b0}}-8-StartY);
    assign MiddleBallY = (qDX == {WIDTH_VIDEO{1'b0}}-8-StartX);

    always @ (posedge clock) begin
	// Full sides.
	TopBall = (qDY == {WIDTH_VIDEO{1'b0}}   -StartY) && HorzBall;
	BotBall = (qDY == {WIDTH_VIDEO{1'b0}}-17-StartY) && HorzBall;
	LftBall = (qDX == {WIDTH_VIDEO{1'b0}}   -StartX) && VertBall;
	RgtBall = (qDX == {WIDTH_VIDEO{1'b0}}-17-StartX) && VertBall;

	MTopBall = (qDY == {WIDTH_VIDEO{1'b0}}   -StartY) && MiddleBallY;
	MBotBall = (qDY == {WIDTH_VIDEO{1'b0}}-17-StartY) && MiddleBallY;
	MLftBall = (qDX == {WIDTH_VIDEO{1'b0}}   -StartX) && MiddleBallX;
	MRgtBall = (qDX == {WIDTH_VIDEO{1'b0}}-17-StartX) && MiddleBallX;

	// Ball shade.
	HorzShade = MiddleBallY ||
		    (HorzShade && !(qDX == {WIDTH_VIDEO{1'b0}}-8-StartX-16));
	VertShade = MiddleBallX ||
		    (VertShade && !(qDY == {WIDTH_VIDEO{1'b0}}-8-StartY-16));
    end

    // Counters.
    always @ (posedge clock) begin
	if (HSync) qDX = BallPosX; else qDX = qDX - 1;
	if (SSMoveBall == LoadY) qDY = BallPosY; else if (HSync) qDY = qDY - 1;
    end

    always @ (posedge clock) begin
	assign BitRaster = HorzBall && VertBall;
	assign BitRasterShade = HorzShade && VertShade;
    end

    // Outputs.

    assign BRLftBall = LftBall;
    assign BRRgtBall = RgtBall;

endmodule // Ball
