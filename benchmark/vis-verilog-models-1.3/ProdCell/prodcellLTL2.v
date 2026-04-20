/*
 * prodcell.v
 *
 * Model of the production cell circuit. Based on the description given in:
 *
 * @InBook{Lindne94,
 *   author =       {Thomas Lindner},
 *   title =        {Case Study "Production Cell": A Comparative Study in Formal
 *                   Software Development},
 *   chapter =      2,
 *   publisher =    {FZI},
 *   year =         1994,
 *   pages =        {9,21}
 * }
 * 
 * author: Abelardo Pardo (abel@vlsi.colorado.edu)
 * date: 96/8/16
 *
 * 
 */

/* Types */
typedef enum {Y, N} sensor;
typedef enum {on, off} switch;
typedef enum {GoLeft, GoRight, Stop} craneHorizontalMovement;
typedef enum {GoUp, GoDown, Stop} craneVerticalMovement;
typedef enum {Grab, Free} craneGrip;
typedef enum {OverFB, Middle, OverDB} tcHorizontalPosition;
typedef enum {UpMost, DBHight, FBHight} tcVerticalPosition;
typedef enum {E, F} unitInBelt;
typedef enum {S, SSE, SE} rtAnglePosition;
typedef enum {Top, Mid, Bot} rtVerticalPosition;
typedef enum {CWise, Stop, CCWise} rtAngleMovement;
typedef enum {GoUp, GoDown, Stop} rtVerticalMovement;
typedef enum {GoUp, GoDown, Stop} pressVerticalMovement;
typedef enum {Top, Mid, Bot} pressVerticalPosition;
typedef enum {Extend, Retract, Stop} armHorizontalMovement;
typedef enum {OverRT, OverLoadedPress, OverDB, OverUnLoadedPress} armAnglePosition;
typedef enum {CWise, Stop, CCWise} armAngleMovement;
typedef enum {Extended, Retracted, Middle} armPosition;

/*
 * 
 * MAIN MODULE: The production cell
 * 
 */
module ProductionCell(clk,fair);
   input clk;
   output fair;
   
   sensor wire PieceOutDB;
   sensor wire PieceOutFB;
   sensor wire PieceGrabbedFromDB;
   sensor wire PieceGrabbedFromRT;
   sensor wire PieceGrabbedFromFB;
   sensor wire FBReady;
   sensor wire PieceOutArm;
   sensor wire PieceReleasedOnFB;
   sensor wire DBReady;
   sensor wire ArmUnLoadedPress;
   sensor wire PressReadyToBeUnLoaded;
   sensor wire ArmLoadedPress;
   sensor wire PressReadyToBeLoaded;
   sensor wire RTOutReady;
   
   TravellingCraneSet TC(clk, PieceOutDB, FBReady, PieceGrabbedFromDB,
			 PieceReleasedOnFB);

   DepositBeltSet DB(clk, PieceGrabbedFromDB, PieceOutArm, PieceOutDB,
		     DBReady);
   
   FeedBeltSet FB(clk, PieceGrabbedFromFB, PieceReleasedOnFB, FBReady, 
		  PieceOutFB);
   
   RotaryTableSet RT(clk, PieceOutFB, PieceGrabbedFromRT,
		     PieceGrabbedFromFB, RTOutReady);
   
   PressSet PR(clk, ArmLoadedPress, ArmUnLoadedPress,
	       PressReadyToBeLoaded, PressReadyToBeUnLoaded);
   
   ArmSet AR(clk, DBReady, PressReadyToBeUnLoaded, PressReadyToBeLoaded, 
	     RTOutReady, PieceOutArm, ArmUnLoadedPress, ArmLoadedPress, 
	     PieceGrabbedFromRT);

   monitor mtr(clk, fair, DBReady, PieceOutArm);

endmodule // ProductionCell


// Automaton to check the property
//  G(DBReady -> (DBReady U PieceOutArm))
module monitor(clk, fair, DBReady, PieceOutArm);
    input clk;
    input DBReady;
    input PieceOutArm;
    output fair;

    sensor wire DBReady;
    sensor wire PieceOutArm;

    reg [1:0] state;

    initial state = 0;

    assign fair = state == 3;

    always @ (posedge clk)
      case (state)
	0: state = DBReady == Y ? 1 : 0;
	1: state = PieceOutArm == Y ? 2 : DBReady == Y ? 1 : 3;
	2: state = 2;
	3: state = 3;
      endcase // case(state)

endmodule // monitor


/*
 * 
 * TRAVELLING CRANE SET
 * 
 */
module TravellingCraneSet(clk, PieceOutDB, FBReady, PieceGrabbedFromDB,
			  PieceReleasedOnFB);
   input  clk;
   input  PieceOutDB;
   input  FBReady;
   output PieceGrabbedFromDB;
   output PieceReleasedOnFB;

   sensor wire PieceOutDB;
   sensor wire FBReady;
   sensor wire PieceGrabbedFromDB;
   sensor wire PieceReleasedOnFB;

   craneHorizontalMovement wire HorizontalMove;
   craneVerticalMovement wire VerticalMove;
   
   sensor wire CraneOnTheLeft;
   sensor wire CraneOnTheRight;
   
   tcVerticalPosition wire VerticalPos;

   TravellingCrane Crane(clk, HorizontalMove, VerticalMove,
		   CraneOnTheLeft, CraneOnTheRight, VerticalPos);

   TravellingCraneCNTR CraneCNTR(clk, FBReady, PieceOutDB, CraneOnTheLeft, 
				 CraneOnTheRight, VerticalPos, HorizontalMove, 
				 VerticalMove, PieceReleasedOnFB, 
				 PieceGrabbedFromDB);
endmodule // TravellingCraneSet

/*
 * 
 * TRAVELLING CRANE
 * 
 */
module TravellingCrane(clk, HorizontalMove, VerticalMove, CraneOnTheLeft, 
		       CraneOnTheRight, VerticalPos);
   input  clk;
   input  HorizontalMove;  /* Signal controlling the horizontal movement */
   input  VerticalMove;    /* Signal controlling the vertical movement */
   output CraneOnTheLeft;  /* Crane is on the left most position */
   output CraneOnTheRight; /* Crane is on the right most position */
   output VerticalPos;     /* Which vertical position the crane is */
   
   craneHorizontalMovement wire HorizontalMove;
   craneVerticalMovement wire VerticalMove;
   
   sensor wire CraneOnTheLeft;
   sensor wire CraneOnTheRight;
   
   tcHorizontalPosition reg HorizontalPos;
   tcVerticalPosition reg VerticalPos;

   assign CraneOnTheLeft = (HorizontalPos == OverFB) ? Y : N;
   assign CraneOnTheRight = (HorizontalPos == OverDB) ? Y : N;
   
   initial
      begin
	 HorizontalPos = $ND(Middle, OverDB, OverFB);
	 VerticalPos = UpMost;
      end // initial

   always @(posedge clk) begin
	  
      /* Horizontal Movement evolution */
      if (HorizontalMove == GoLeft) begin
	 if (HorizontalPos == Middle) begin
	    HorizontalPos = OverFB;
	 end // if (HorizontalPos == Middle)
	 else if (HorizontalPos == OverDB) begin
	    HorizontalPos = Middle;
	 end // if (HorizontalPos == OverDB)
      end // if (HorizontalMove == GoLeft)

      if (HorizontalMove == GoRight) begin
	 if (HorizontalPos == Middle) begin
	    HorizontalPos = OverDB;
	 end // if (HorizontalPos == Middle)
	 else if (HorizontalPos == OverFB) begin
	    HorizontalPos = Middle;
	 end // if (HorizontalPos == OverFB)
      end // if (HorizontalMove == GoRight)

      /* VerticalMovement Evolution */
      if (VerticalMove == GoUp) begin
	 if (VerticalPos == DBHight) begin
	    VerticalPos = UpMost;
	 end // if (VerticalPos == DBHight)
	 else if (VerticalPos == FBHight) begin
	    VerticalPos = DBHight;
	 end // if (VerticalPos == FBHight)
      end // if (VerticalMove == GoUp)

      if (VerticalMove == GoDown) begin
	 if (VerticalPos == UpMost) begin
	    VerticalPos = DBHight;
	 end // if (VerticalPos == UpMost)
	 else if (VerticalPos == DBHight) begin
	    VerticalPos = FBHight;
	 end // else: !if(VerticalPos == UpMost)
      end // if (VerticalMove == GoDown)
      
   end      
endmodule // TravellingCrane

/*
 * 
 * TRAVELLING CRANE CONTROLER
 * 
 */
module TravellingCraneCNTR(clk, FBReady, PieceOutDB, CraneOnTheLeft, 
			   CraneOnTheRight, VerticalPos, HorizontalMove, 
			   VerticalMove, PieceReleasedOnFB, PieceGrabbedFromDB);
   input  clk;             
   input  FBReady;            /* FBBelt is ready to receive a piece */
   input  PieceOutDB;         /* Deposit Belt has a piece to be picked up */
   input  CraneOnTheLeft;     /* Crane is on the left most position */
   input  CraneOnTheRight;    /* Crane is on the right most position */
   input  VerticalPos;        /* Register storing crane's vertical position */
   output HorizontalMove;     /* Register controlling the horizontal motor */
   output VerticalMove;       /* Register controlling the vertical motor */
   output PieceReleasedOnFB;  /* A piece was dropped on FB */
   output PieceGrabbedFromDB; /* A piece has been grabbed from DB */
   
   sensor wire FBReady;
   sensor wire CraneOnTheLeft;
   sensor wire CraneOnTheRight;
   tcVerticalPosition wire VerticalPos;
   sensor wire PieceOutDB;

   craneHorizontalMovement reg HorizontalMove;
   craneVerticalMovement reg VerticalMove;
   craneGrip reg Grip;
   sensor reg PieceReleasedOnFB;
   sensor reg PieceGrabbedFromDB;
   
   
   initial
      begin
	 HorizontalMove = Stop;
	 VerticalMove = Stop;
	 Grip = Free;
	 PieceReleasedOnFB = N;
	 PieceGrabbedFromDB = N;
      end // initial

   always @(posedge clk) begin
      case (Grip)
	Grab: begin
	   case (VerticalPos)
	     UpMost: begin
		Grip = Grab;
		if (CraneOnTheLeft == Y) begin
		   HorizontalMove = Stop;
		   VerticalMove = GoDown;
		end // if (CraneOnTheLeft)
		else begin
		   if (CraneOnTheRight == N && CraneOnTheLeft == N) begin
		      if (HorizontalMove == GoLeft) begin
			 HorizontalMove = Stop;
			 VerticalMove = GoDown;
		      end // if (HorizontalMove == GoLeft)
		      else begin
			 HorizontalMove = GoLeft;
			 VerticalMove = Stop;
		      end // else: !if(HorizontalMove == GoLeft)
		   end // if (CraneOnTheRight == N && CraneOnTheLeft == N)
		   else begin
		      HorizontalMove = GoLeft;
		      VerticalMove = Stop;
		   end // else: !if(CraneOnTheRight == N && CraneOnTheLeft == Y)
		end // else: !if(CraneOnTheLeft == Y)
	     end // case: UpMost
	     DBHight: begin
		if (CraneOnTheLeft == Y) begin
		   if (VerticalMove == GoDown) begin
		      if (FBReady == Y) begin
			 Grip = Free;
			 HorizontalMove = Stop;
			 VerticalMove = GoUp;
			 PieceReleasedOnFB = Y;
		      end // if (FBReady == Y)
		      else begin
			 Grip = Grab;
			 HorizontalMove = Stop;
			 VerticalMove = Stop;
		      end // else: !if(FBReady == Y)
		   end // if (VerticalMove == GoDown)
		   else begin
		      Grip = Grab;
		      HorizontalMove = Stop;
		      VerticalMove = GoDown;
		   end // else: !if(VerticalMove == GoDown)
		end // if (CraneOnTheLeft == Y)
		if (CraneOnTheRight == Y) begin
		   Grip = Grab;
		   if (VerticalMove == GoUp) begin
		      HorizontalMove = GoLeft;
		      VerticalMove = Stop;
		   end // if (VerticalMove == GoUp)
		   else begin
		      HorizontalMove = Stop;
		      VerticalMove = GoUp;
		   end // else: !if(VerticalMove == GoUp)
		end // if (CraneOnTheRight == Y)
	     end // case: DBHight
	     FBHight: begin
		if (CraneOnTheLeft == Y) begin
		   if (FBReady == Y) begin
		      Grip = Free;
		      HorizontalMove = Stop;
		      VerticalMove = GoUp;
		      PieceReleasedOnFB = Y;
		   end // if (FBReady == Y)
		   else begin
		      Grip = Grab;
		      HorizontalMove = Stop;
		      VerticalMove = Stop;
		   end // else: !if(FBReady == Y)
		end // if (CraneOnTheLeft == Y)
	     end // case: FBHight
	   endcase // _case (VerticalPos)
	end // case: Grab
	Free: begin
	   case (VerticalPos)
	     UpMost: begin
		if (CraneOnTheLeft == Y) begin
		   Grip = Free;
		   HorizontalMove = GoRight;
		   VerticalMove = Stop; 
		end // if (CraneOnTheLeft == Y)
		else begin
		   if (CraneOnTheRight == N) begin
		      if (HorizontalMove == GoRight) begin
			 Grip = Free;
			 HorizontalMove = Stop;
			 VerticalMove = GoDown;
		      end // if (HorizontalMove == GoRight)
		      else begin
			 Grip = Free;
			 HorizontalMove = GoRight;
			 VerticalMove = Stop;
		      end // else: !if(HorizontalMove = GoRight)
		   end // if (CraneOnTheRight == N)
		   else begin
		      if (PieceOutDB == Y) begin
			 if (VerticalMove == GoDown) begin
			    Grip = Grab;
			    HorizontalMove = Stop;
			    VerticalMove = GoUp;
			    PieceGrabbedFromDB = Y;
			 end // if (VerticalMove == GoDown)
			 else begin
			    Grip = Free;
			    HorizontalMove = Stop;
			    VerticalMove = GoDown;
			 end // else: !if(VerticalMove == GoDown)
		      end // if (PieceOutDB == Y)
		      else begin
			 Grip = Free;
			 HorizontalMove = Stop;
			 if (VerticalMove == GoDown) begin
			    VerticalMove = Stop;
			 end // if (VerticalMove == GoDown)
			 else begin 
			    VerticalMove = GoDown;
			 end // else: !if(VerticalMove == GoDown)
		      end // else: !if(PieceOutDB == Y)
		   end // else: !if(CraneOnTheRight == N)
		end // else: !if(CraneOnTheLeft == Y)
	     end // case: UpMost
	     DBHight: begin
		if (CraneOnTheLeft == Y) begin
		   Grip = Free;
		   if (VerticalMove == GoUp) begin
		      HorizontalMove = GoRight;
		      VerticalMove = Stop;
		   end // if (VerticalMove == GoUp)
		   else begin
		      HorizontalMove = Stop;
		      VerticalMove = GoUp;
		   end // else: !if(VerticalMove == GoUp)
		end // if (CraneOnTheLeft == Y)
		if (CraneOnTheRight == Y) begin
		   if (PieceOutDB == Y) begin
		      Grip = Grab;
		      HorizontalMove = Stop;
		      VerticalMove = GoUp;
		      PieceGrabbedFromDB = Y;
		   end // if (PieceOutDB == Y)
		   else begin
		      Grip = Free;
		      HorizontalMove = Stop;
		      VerticalMove = Stop;
		   end // else: !if(PieceOutDB == Y)
		end // if (CraneOnTheRight == Y)
	     end // case: DBHight
	     FBHight: begin
		if (CraneOnTheLeft == Y) begin
		   Grip = Free;
		   HorizontalMove = Stop;
		   VerticalMove = GoUp;
		end // if (CraneOnTheLeft == Y)
	     end // case: FBHight
	   endcase // _case (VerticalPos)
	end // case: Free
	endcase // _case (Grip)

      /* Complete the handshake with both belts */
      if (PieceOutDB == N && PieceGrabbedFromDB == Y) begin
	 PieceGrabbedFromDB = N;
      end // if (PieceOutDB == N && PieceGrabbedFromDB == Y)

      if (FBReady == N && PieceReleasedOnFB == Y) begin
	 PieceReleasedOnFB = N;
      end // if (FBReady == N && PieceReleasedOnFB == Y)
      
      
   end // always @ (posedge clk)
endmodule // TravellingCraneCNTR

/*
 * 
 * DEPOSIT BELT SET
 * 
 */
module DepositBeltSet(clk, PieceGrabbedFromDB, PieceOutArm,
		      PieceOutDB, DBReady);
   input  clk;
   input  PieceGrabbedFromDB;
   input  PieceOutArm;
   output PieceOutDB;
   output DBReady;
   
   sensor wire PieceGrabbedFromDB;
   sensor wire PieceOutArm;
   sensor wire PieceOutDB;
   sensor wire DBReady;
   
   switch wire DBMotorSwitch;
   unitInBelt wire DBelt0, DBelt1, DBelt2, DBelt3;

   DepositBelt DBelt(clk, DBMotorSwitch, PieceOutArm, PieceGrabbedFromDB, 
		     DBReady, DBelt0, DBelt1, DBelt2, DBelt3);

   DepositBeltCNTR DBeltCNTR(clk, DBelt0, DBelt1, DBelt2, DBelt3, 
			     PieceGrabbedFromDB, PieceOutArm, DBMotorSwitch,
			     DBReady, PieceOutDB);
endmodule // DepositBeltSet

/*
 * 
 * DEPOSIT BELT
 * 
 */
module DepositBelt(clk, DBMotorSwitch, PieceOutArm, PieceGrabbedFromDB,
		   DBReady, DBelt0, DBelt1, DBelt2, DBelt3);
   input  clk;
   input  DBMotorSwitch;      /* Signal controlling the motor */
   input  PieceOutArm;        /* Presence of a piece in the arm */
   input  PieceGrabbedFromDB; /* Piece was grabbed from the DB */
   input  DBReady;            /* The belt is ready to accept another piece */
   output DBelt0, DBelt1, DBelt2, DBelt3; /* Positions on the belt */
   
   switch wire DBMotorSwitch;
   sensor wire PieceOutArm;
   sensor wire PieceGrabbedFromDB;
   sensor wire DBReady;
   
   unitInBelt reg DBelt0, DBelt1, DBelt2, DBelt3;

   initial
      begin
	 DBelt0 = F;
	 DBelt1 = $ND(E,F);
	 DBelt2 = $ND(E,F);
	 DBelt3 = $ND(E,F);
      end // initial

   always @(posedge clk) begin

      /* Motion produced by the motor */
      if (DBMotorSwitch == on) begin
	 DBelt0 = DBelt1;
	 DBelt1 = DBelt2;
	 DBelt2 = DBelt3;
	 DBelt3 = E;
      end // if (DBMotorSwitch == on)
      if (DBelt3 == E && PieceOutArm == Y && DBReady == Y) begin
	 DBelt3 = F;
      end // if (DBelt3 == E && PieceOutArm == Y && DBReady == N)
      
      /* The piece was grabbed by the crane */
      if (PieceGrabbedFromDB == Y) begin
	 DBelt0 = E;
      end // if (PieceGrabbedFromDB == Y)
   end // always @ (posedge clk)
endmodule // DepositBelt


/*
 * 
 * DEPOSIT BELT CONTROL
 * 
 */
module DepositBeltCNTR(clk, DBelt0, DBelt1, DBelt2, DBelt3,
		       PieceGrabbedFromDB, PieceOutArm, DBMotorSwitch, 
		       DBReady, PieceOutDB);
   input  clk;
   input  DBelt0, DBelt1, DBelt2, DBelt3; /* Belt positions */
   input  PieceGrabbedFromDB; /* A piece was grabbed from the DB */
   input  PieceOutArm;        /* A piece is ready to be picked up by the belt */
   output DBMotorSwitch;      /* Signal controlling the motor */
   output DBReady;            /* The belt is ready to accept another piece */
   output PieceOutDB;         /* Piece ready to be picked by the crane */
   
   unitInBelt wire DBelt0, DBelt1, DBelt2, DBelt3;
   sensor wire PieceGrabbedFromDB;
   sensor wire PieceOutArm;
   
   switch reg DBMotorSwitch;
   sensor reg DBReady;
   sensor reg PieceOutDB;         
   
   initial
      begin
	 DBMotorSwitch = off;
	 DBReady = N;
	 PieceOutDB = N;
      end

   always @(posedge clk) begin

      /* Control the handshake with the crane */
      if ((DBelt0 == F && PieceOutDB == N && PieceGrabbedFromDB == N) ||
	  (DBelt0 == E && DBelt1 == F && DBMotorSwitch == on && 
	   PieceOutDB == N && PieceGrabbedFromDB == N)) begin
	 PieceOutDB = Y;
      end // if (DBelt0 == F && PieceOutDB == N && PieceGrabbedFromDB == N)
      
      if (PieceOutDB == Y && PieceGrabbedFromDB == Y) begin
	 PieceOutDB = N;
      end // if (PieceOutDB == Y && PieceGrabbedFromDB == Y)


      /* Control The handshake with the Arm */
      if (DBelt3 == E && PieceOutArm == N && DBReady == N) begin
	 DBReady = Y;
      end // if (DBelt3 == E && PieceOutArm == Y && DBReady == N)

      if (PieceOutArm == Y && DBReady == Y) begin
	 DBReady = N;
	 DBMotorSwitch = on;
      end // if (PieceOutArm == Y && DBReady == Y)

      /* Control the motor */
      if (DBelt0 == F) begin
	 DBMotorSwitch = off;
      end // if (DBelt0 == F)
      
      if (DBelt0 == E && DBMotorSwitch == off && (DBelt1 == F || DBelt2 == F 
						  || DBelt3 == F)) begin
	 DBMotorSwitch = on;
      end
      else begin
	 if (DBelt0 == E && DBMotorSwitch == on && DBelt1 == F) begin
	    DBMotorSwitch = off;
	 end // if (DBelt0 == E && DBMotorSwitch == on && DBelt1 == F)
      end 
      
   end // always @ (posedge clk)
endmodule // DepositBeltCNTR

/*
 * 
 * FEED BELT SET
 * 
 */
module FeedBeltSet(clk, PieceGrabbedFromFB, PieceReleasedOnFB,
		   FBReady, PieceOutFB);
   input  clk;
   input  PieceGrabbedFromFB;
   input  PieceReleasedOnFB;
   output FBReady;
   output PieceOutFB;

   sensor wire PieceGrabbedFromFB;
   sensor wire PieceReleasedOnFB;
   sensor wire FBReady;
   sensor wire PieceOutFB;
   
   switch wire FBMotorSwitch;
   unitInBelt wire FBelt0, FBelt1, FBelt2, FBelt3;

   FeedBelt FBelt(clk, FBMotorSwitch, PieceReleasedOnFB, PieceGrabbedFromFB,
		  FBReady, FBelt0, FBelt1, FBelt2, FBelt3);

   FeedBeltCNTR FBeltCNTR(clk, FBelt0, FBelt1, FBelt2, FBelt3,
			  PieceGrabbedFromFB, PieceReleasedOnFB, FBMotorSwitch,
			  FBReady, PieceOutFB);
endmodule // FeedBeltSet


/*
 * 
 * FEED BELT
 * 
 */
module FeedBelt(clk, FBMotorSwitch, PieceReleasedOnFB, PieceGrabbedFromFB,
		   FBReady, FBelt0, FBelt1, FBelt2, FBelt3);
   input  clk;
   input  FBMotorSwitch;      /* Motor controlling the belt */
   input  PieceReleasedOnFB;  /* Piece has been released in the belt */
   input  PieceGrabbedFromFB; /* Piece was picked from the belt */
   input  FBReady;            /* Belt is ready to accept another piece */
   output FBelt0, FBelt1, FBelt2, FBelt3; /* Belt positions */
   
   switch wire FBMotorSwitch;
   sensor wire PieceReleasedOnFB;
   sensor wire PieceGrabbedFromFB;
   sensor wire FBReady;
   
   unitInBelt reg FBelt0, FBelt1, FBelt2, FBelt3;

   initial
      begin
	 FBelt0 = $ND(E,F);
	 FBelt1 = $ND(E,F);
	 FBelt2 = $ND(E,F);
	 FBelt3 = $ND(E,F);
      end // initial

   always @(posedge clk) begin

      /* Motion produced by the motor */
      if (FBMotorSwitch == on) begin
	 FBelt0 = FBelt1;
	 FBelt1 = FBelt2;
	 FBelt2 = FBelt3;
	 FBelt3 = E;
      end // if (FBMotorSwitch == on)
      if (FBelt3 == E && PieceReleasedOnFB == Y && FBReady == Y) begin
	 FBelt3 = F;
      end // if (FBelt3 == E && PieceReleasedOnFB == Y && FBReady == N)
      
      /* The piece was grabbed by the crane */
      if (PieceGrabbedFromFB == Y) begin
	 FBelt0 = E;
      end // if (PieceGrabbedFromFB == Y)
   end // always @ (posedge clk)
endmodule // FeedBelt


/*
 * 
 * FEED BELT CONTROL
 * 
 */
module FeedBeltCNTR(clk, FBelt0, FBelt1, FBelt2, FBelt3,
		       PieceGrabbedFromFB, PieceReleasedOnFB, FBMotorSwitch, 
		       FBReady, PieceOutFB);
   input  clk;
   input  FBelt0, FBelt1, FBelt2, FBelt3; /* Belt Positions */
   input  PieceGrabbedFromFB; /* Piece was grabbed from the belt */
   input  PieceReleasedOnFB;  /* Piece was deposited on the belt */
   output FBMotorSwitch;      /* Signal controlling the motor */
   output FBReady;            /* Belt is ready to receive another piece */
   output PieceOutFB;         /* Belt has a piece ready to be picked */
   
   unitInBelt wire FBelt0, FBelt1, FBelt2, FBelt3;
   sensor wire PieceGrabbedFromFB;
   sensor wire PieceReleasedOnFB;
   
   switch reg FBMotorSwitch;
   sensor reg FBReady;
   sensor reg PieceOutFB;         
   
   initial
      begin
	 FBMotorSwitch = off;
	 FBReady = N;
	 PieceOutFB = N;
      end

   always @(posedge clk) begin

      /* Control the handshake with the crane */
      if ((FBelt0 == F && PieceOutFB == N && PieceGrabbedFromFB == N) ||
	  (FBelt0 == E && FBelt1 == F && FBMotorSwitch == on && 
	   PieceOutFB == N && PieceGrabbedFromFB == N)) begin
	 PieceOutFB = Y;
      end // if (FBelt0 == F && PieceOutFB == N && PieceGrabbedFromFB == N)
      
      if (PieceOutFB == Y && PieceGrabbedFromFB == Y) begin
	 PieceOutFB = N;
      end // if (PieceOutFB == Y && PieceGrabbedFromFB == Y)


      /* Control The handshake with the Arm */
      if (FBelt3 == E && PieceReleasedOnFB == N && FBReady == N) begin
	 FBReady = Y;
      end // if (FBelt3 == E && PieceReleasedOnFB == Y && FBReady == N)

      if (PieceReleasedOnFB == Y && FBReady == Y) begin
	 FBReady = N;
	 FBMotorSwitch = on;
      end // if (PieceReleasedOnFB == Y && FBReady == Y)

      /* Control the motor */
      if (FBelt0 == F) begin
	 FBMotorSwitch = off;
      end // if (FBelt0 == F)
      
      if (FBelt0 == E && FBMotorSwitch == off && (FBelt1 == F || FBelt2 == F 
						  || FBelt3 == F)) begin
	 FBMotorSwitch = on;
      end // if (FBelt0 == E && FBMotorSwitch == off &&...
      else begin
	 if (FBelt0 == E && FBMotorSwitch == on && FBelt1 == F) begin
	    FBMotorSwitch = off;
	 end // if (FBelt0 == E && FBMotorSwitch == on && FBelt1 == F)
      end 
      
   end // always @ (posedge clk)
endmodule // FeedBeltCNTR

/*
 * 
 * ROTARY TABLE SET
 * 
 */
module RotaryTableSet(clk, PieceOutFB, PieceGrabbedFromRT, 
		 PieceGrabbedFromFB, RTOutReady);
   input  clk;
   input  PieceOutFB;
   input  PieceGrabbedFromRT;
   output PieceGrabbedFromFB;
   output RTOutReady;
   
   sensor wire PieceOutFB;
   sensor wire PieceGrabbedFromRT;
   sensor wire PieceGrabbedFromFB;
   sensor wire RTOutReady;

   rtAngleMovement wire RTRotaryMotor;
   rtVerticalMovement wire RTVerticalMotor;

   sensor wire RTOnFB;
   sensor wire RTOnArm;
   sensor wire RTOnTop;
   sensor wire RTOnBottom;

   RotaryTable RTable(clk, RTRotaryMotor, RTVerticalMotor,
		      RTOnFB, RTOnArm, RTOnTop, RTOnBottom);
   RotaryTableCNTR RTableCNTR(clk, PieceOutFB, PieceGrabbedFromRT,
			      RTOnFB, RTOnArm, RTOnTop, RTOnBottom,
			      RTRotaryMotor, RTVerticalMotor,
			      PieceGrabbedFromFB, RTOutReady);
endmodule // RotarySet

/*
 * 
 * ROTARY TABLE
 * 
 */
module RotaryTable(clk, RTRotaryMotor, RTVerticalMotor, 
		   RTOnFB, RTOnArm, RTOnTop, RTOnBottom);
   input  clk;
   input  RTRotaryMotor;
   input  RTVerticalMotor;
   output RTOnFB;
   output RTOnArm;
   output RTOnTop;
   output RTOnBottom;

   rtAngleMovement wire RTRotaryMotor;
   rtVerticalMovement wire RTVerticalMotor;

   sensor wire RTOnFB;
   sensor wire RTOnArm;
   sensor wire RTOnTop;
   sensor wire RTOnBottom;

   rtAnglePosition reg RTAngle;
   rtVerticalPosition reg RTHight;
   
   assign RTOnFB = (RTAngle == S) ? Y : N;
   assign RTOnArm = (RTAngle == SE) ? Y : N;
   assign RTOnTop = (RTHight == Top) ? Y : N;
   assign RTOnBottom = (RTHight == Bot) ? Y : N;
   
   initial
      begin
	 RTAngle = $ND(S, SSE, SE);
	 RTHight = $ND(Top, Mid, Bot);
      end // initial

   always @(posedge clk) begin
      /* Rotary movement of the table */
      if (RTRotaryMotor == CWise) begin
	 case (RTAngle)
	   SE: begin
	      RTAngle = SSE;
	   end
	   SSE: begin 
	      RTAngle = S;
	   end
	 endcase // _case (RTAngle)
      end // if (RTRotaryMotor == CWise)

      if (RTRotaryMotor == CCWise) begin
	 case (RTAngle)
	   S: begin
	      RTAngle = SSE;
	   end
	   SSE: begin 
	      RTAngle = SE;
	   end
	 endcase // _case (RTAngle)
      end // if (RTRotaryMotor == CCWise)

      /* Vertical Movement of the table */
      if (RTVerticalMotor == GoUp) begin
	 case (RTHight)
	   Mid: begin 
	      RTHight = Top;
	   end
	   Bot: begin
	      RTHight = Mid;
	   end
	 endcase // _case (RTHight)
      end // if (RTVerticalMotor == GoUp)

      if (RTVerticalMotor == GoDown) begin
	 case (RTHight)
	   Mid: begin
	      RTHight = Bot;
	   end
	   Top: begin
	      RTHight = Mid;
	   end
	 endcase // _case (RTHight)
      end // if (RTVerticalMotor = GoDown)
   end // always @ (posedge clk)
endmodule // RotaryTable

/*
 * 
 * ROTARY TABLE CNTR
 * 
 */
module RotaryTableCNTR(clk, PieceOutFB, PieceGrabbedFromRT,
		       RTOnFB, RTOnArm, RTOnTop, RTOnBottom,
		       RTRotaryMotor, RTVerticalMotor,
		       PieceGrabbedFromFB, RTOutReady);
   input  clk;
   input  PieceOutFB;
   input  PieceGrabbedFromRT;
   input  RTOnFB;
   input  RTOnArm;
   input  RTOnTop;
   input  RTOnBottom;
   output RTRotaryMotor;
   output RTVerticalMotor;
   output PieceGrabbedFromFB;
   output RTOutReady;

   sensor wire PieceOutFB;
   sensor wire PieceGrabbedFromRT;
   sensor wire RTOnFB;
   sensor wire RTOnArm;
   sensor wire RTOnBottom;
   sensor wire RTOnTop;

   rtAngleMovement wire CWiseChoice;
   rtAngleMovement wire CCWiseChoice;
   rtVerticalMovement wire UpChoice;
   rtVerticalMovement wire DownChoice;
   
   rtAngleMovement reg RTRotaryMotor;
   rtVerticalMovement reg RTVerticalMotor;
   sensor reg PieceGrabbedFromFB;
   sensor reg RTOutReady;
   sensor reg TableLoaded;
   
//   assign CWiseChoice = $ND(Stop, CWise);
//   assign CCWiseChoice = $ND(Stop, CCWise);
//   assign UpChoice = $ND(Stop, GoUp);
//   assign DownChoice = $ND(Stop, GoDown);
   assign CWiseChoice = CWise;
   assign CCWiseChoice = CCWise;
   assign UpChoice = GoUp;
   assign DownChoice = GoDown;
   
   initial
      begin
	 RTRotaryMotor = Stop;
	 RTVerticalMotor = Stop;
	 PieceGrabbedFromFB = N;
	 RTOutReady = N;
	 TableLoaded = N;
      end // initial

   always @(posedge clk) begin
      case (TableLoaded)
	Y: begin
	   if (RTOnTop == Y) begin
	      if (RTOnFB == Y) begin
		 RTRotaryMotor = CCWise;
		 RTVerticalMotor = Stop;
	      end // if (RTOnFB == Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 RTVerticalMotor = Stop;
		 if (RTRotaryMotor == CCWise) begin
		    RTRotaryMotor = Stop;
		    RTOutReady = Y;
		 end // if (RTRotaryMotor == CCWise)
		 else begin
		    if (RTRotaryMotor == Stop) begin
		       RTRotaryMotor = CCWise;
		    end // if (RTRotaryMotor == Stop)
		 end // else: !if(RTRotaryMotor == CCWise)
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 if (PieceGrabbedFromRT == Y) begin
		    RTOutReady = N;
		    RTRotaryMotor = CWiseChoice;
		    RTVerticalMotor = DownChoice;
		    TableLoaded = N;
		 end // if (PieceGrabbedFromRT == Y)
		 else begin
		    RTOutReady = Y;
		    RTRotaryMotor = Stop;
		    RTVerticalMotor = Stop;
		 end // else: !if(PieceGrabbedFromRT == Y)
	      end // if (RTOnArm == Y)
	   end // if (RTOnTop == Y)
	   if (RTOnTop == N && RTOnBottom == N) begin
	      if (RTOnFB ==Y) begin
		 RTRotaryMotor = CCWiseChoice;
		 if (RTVerticalMotor == GoUp) begin
		    RTVerticalMotor = Stop;
		 end // if (RTVerticalMotor == GoUp)
		 else begin
		    RTVerticalMotor = UpChoice;
		 end // else: !if(RTVerticalMotor == GoUp)
	      end // if (RTOnFB ==Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 if (RTRotaryMotor == CCWise && RTVerticalMotor == GoUp) begin
		    RTRotaryMotor = Stop;
		    RTVerticalMotor = Stop;
		    RTOutReady = Y;
		 end // if (RTRotaryMotor == CCWise && RTVerticalMotor == GoUp)
		 else begin
		    if (RTRotaryMotor!=CCWise && RTVerticalMotor == GoUp) begin
		       RTRotaryMotor = CCWiseChoice;
		       RTVerticalMotor = Stop;
		    end 
		    else begin
		       if (RTRotaryMotor == CCWise && 
			   RTVerticalMotor != GoUp) begin
			  RTRotaryMotor = Stop;
			  RTVerticalMotor = UpChoice;
		       end 
		       else begin
			  if (RTRotaryMotor != CCWise 
			      && RTVerticalMotor != GoUp) begin
			     RTRotaryMotor = CCWiseChoice;
			     RTVerticalMotor = UpChoice;
			  end 
		       end 
		    end 
		 end 
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 RTRotaryMotor = Stop;
		 if (RTVerticalMotor == GoUp) begin
		    RTVerticalMotor = Stop;
		    RTOutReady = Y;
		 end // if (RTVerticalMotor == GoUp)
		 else begin
		    RTVerticalMotor = GoUp;
		 end // else: !if(RTVerticalMotor == GoUp)
	      end // if (RTOnArm == Y)
	   end // if (RTOnTop == N && RTOnBottom == N)
	   if (RTOnBottom == Y) begin
	      if (RTOnFB == Y) begin
		 RTRotaryMotor = CCWiseChoice;
		 RTVerticalMotor = UpChoice;
	      end // if (RTOnFB == Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 RTVerticalMotor = UpChoice;
		 if (RTRotaryMotor == CCWise) begin
		    RTRotaryMotor = Stop;
		 end // if (RTRotaryMotor == CCWise)
		 else begin
		    RTRotaryMotor = CCWiseChoice;
		 end // else: !if(RTRotaryMotor == CCWise)
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 RTRotaryMotor = Stop;
		 RTVerticalMotor = GoUp;
	      end // if (RTOnArm == Y)
	   end // if (RTOnBottom == Y)
	end // case: Y
	N:begin
	   if (RTOnTop == Y) begin
	      if (RTOnFB == Y) begin
		 RTRotaryMotor = Stop;
		 RTVerticalMotor = GoDown;
	      end // if (RTOnFB == Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 RTVerticalMotor = DownChoice;
		 if (RTRotaryMotor == CWise) begin
		    RTRotaryMotor = Stop;
		 end // if (RTRotaryMotor == CWise)
		 else begin
		    RTRotaryMotor = CWiseChoice;
		 end // else: !if(RTRotaryMotor == CWise)
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 RTRotaryMotor = CWiseChoice;
		 RTVerticalMotor = DownChoice;
	      end // if (RTOnArm == Y)
	   end // if (RTOnTop == Y)
	   if (RTOnTop == N && RTOnBottom == N) begin
	      if (RTOnFB == Y) begin
		 if (RTVerticalMotor == GoDown) begin
		    if (PieceOutFB == Y) begin
		       TableLoaded = Y;
		       PieceGrabbedFromFB = Y;
		       RTRotaryMotor = CCWiseChoice;
		       RTVerticalMotor = UpChoice;
		    end // if (PieceOutFB == Y)
		    else begin
		       RTRotaryMotor = Stop;
		       RTVerticalMotor = Stop;
		    end // else: !if(PieceOutFB == Y)
		 end // if (RTVerticalMotor == GoDown)
		 else begin
		    RTRotaryMotor = Stop;
		    RTVerticalMotor = GoDown;
		 end // else: !if(RTVerticalMotor == GoDown)
	      end // if (RTOnFB == Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 if (RTRotaryMotor == CWise && RTVerticalMotor == GoDown) begin
		    if (PieceOutFB == Y) begin
		       TableLoaded = Y;
		       PieceGrabbedFromFB = Y;
		       RTRotaryMotor = CCWiseChoice;
		       RTVerticalMotor = UpChoice;
		    end // if (PieceOutFB == Y)
		    else begin
		       RTRotaryMotor = Stop;
		       RTVerticalMotor = Stop;
		    end // else: !if(PieceOutFB == Y)
		 end // if (RTRotaryMotor == CWise && RTVerticalMotor == GoDown)
		 else begin
		    if (RTRotaryMotor != CWise && 
			RTVerticalMotor == GoDown) begin
		       RTRotaryMotor = CWiseChoice;
		       RTVerticalMotor = Stop;
		    end 
		    else begin
		       if (RTRotaryMotor == CWise && 
			   RTVerticalMotor != GoDown) begin
			  RTRotaryMotor = Stop;
			  RTVerticalMotor = DownChoice;
		       end 
		       else begin
			  if (RTRotaryMotor != CWise && 
			      RTVerticalMotor != GoDown) begin
			     RTRotaryMotor = CWiseChoice;
			     RTVerticalMotor = DownChoice;
			  end 
		       end 
		    end 
		 end 
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 RTRotaryMotor = CWiseChoice;
		 if (RTVerticalMotor == GoDown) begin
		    RTVerticalMotor = Stop;
		 end // if (RTVerticalMotor == GoDown)
		 else begin
		    RTVerticalMotor = GoDown;
		 end // else: !if(RTVerticalMotor == GoDown)
	      end // if (RTOnArm == Y)
	   end // if (RTOnTop == N && RTOnBottom == N)
	   if (RTOnBottom == Y) begin
	      if (RTOnFB == Y) begin
		 if (PieceOutFB == Y) begin
		    PieceGrabbedFromFB = Y;
		    RTRotaryMotor = CCWiseChoice;
		    RTVerticalMotor = UpChoice;
		    TableLoaded = Y;
		 end // if (PieceOutFB == Y)
		 else begin
		    RTRotaryMotor = Stop;
		    RTVerticalMotor = Stop;
		 end // else: !if(PieceOutFB == Y)
	      end // if (RTOnFB == Y)
	      if (RTOnFB == N && RTOnArm == N) begin
		 RTVerticalMotor = Stop;
		 if (RTRotaryMotor == CWise) begin
		    RTRotaryMotor = Stop;
		 end // if (RTRotaryMotor == CWise)
		 else begin
		    RTRotaryMotor = CWise;
		 end // else: !if(RTRotaryMotor == CWise)
	      end // if (RTOnFB == N && RTOnArm == N)
	      if (RTOnArm == Y) begin
		 RTRotaryMotor = CWise;
		 RTVerticalMotor = Stop;
	      end // if (RTOnArm == Y)
	   end // if (RTOnBottom == Y)
	end // case: N
      endcase // _case (TableLoaded)
      
      if (PieceOutFB == N && PieceGrabbedFromFB == Y) begin
	 PieceGrabbedFromFB = N;
      end // if (PieceOutFB == N && PieceGrabbedFromFB == Y)

      if (RTOutReady == Y && PieceGrabbedFromRT == Y) begin
	 RTOutReady = N;
      end // if (RTOutReady == Y && PieceGrabbedFromRT == Y)
      
   end // always @ (posedge clk)
endmodule // RotaryTableCNTR

/*
 * 
 * PRESS SET
 * 
 */
module PressSet(clk, ArmLoadedPress, ArmUnLoadedPress,
		PressReadyToBeLoaded, PressReadyToBeUnLoaded);
   input  clk;
   input  ArmLoadedPress;
   input  ArmUnLoadedPress;
   output PressReadyToBeLoaded;
   output PressReadyToBeUnLoaded;

   sensor wire ArmLoadedPress;
   sensor wire ArmUnLoadedPress;
   sensor wire PressReadyToBeLoaded;
   sensor wire PressReadyToBeUnLoaded;
   
   pressVerticalMovement wire PressMotor;
   pressVerticalPosition wire PressPosition;

   Press Pr(clk, PressMotor, PressPosition);
   PressCNTR PrCNTR(clk, PressPosition, ArmLoadedPress, ArmUnLoadedPress,
		    PressMotor, PressReadyToBeLoaded, PressReadyToBeUnLoaded);
endmodule // PressSet
   
/*
 * 
 * PRESS
 * 
 */
module Press(clk, PressMotor, PressPosition);
   input  clk;
   input  PressMotor;
   output PressPosition;

   pressVerticalMovement wire PressMotor;

   pressVerticalPosition reg PressPosition;

   initial 
      begin
	 PressPosition = Mid;
      end // initial

   always @(posedge clk) begin
      if (PressMotor == GoUp) begin
	 case (PressPosition)
	   Mid: begin
	      PressPosition = Top;
	   end // case: Mid
	   Bot: begin
	      PressPosition = Mid;
	   end // case: Bot
	 endcase // _case (PressPosition)
      end // if (PressMotor == GoUp)

      if (PressMotor == GoDown) begin
	 case (PressPosition)
	   Top: begin
	      PressPosition = Mid;
	   end // case: Top
	   Mid: begin
	      PressPosition = Bot;
	   end // case: Mid
	 endcase // _case (PressPosition)
      end // if (PressMotor = GoDown)

   end // always @ (posedge clk)
endmodule // Press

/*
 * 
 * PRESS CNTR
 * 
 */
module PressCNTR(clk, PressPosition, ArmLoadedPress, ArmUnLoadedPress,
		 PressMotor, PressReadyToBeLoaded, PressReadyToBeUnLoaded);
   input  clk;
   input  PressPosition;
   input  ArmLoadedPress;
   input  ArmUnLoadedPress;
   output PressMotor;
   output PressReadyToBeLoaded;
   output PressReadyToBeUnLoaded;
   
   pressVerticalPosition wire PressPosition;
   sensor wire ArmLoadedPress;
   sensor wire ArmUnLoadedPress;

   pressVerticalMovement reg PressMotor;
   sensor reg PressReadyToBeLoaded;
   sensor reg PressReadyToBeUnLoaded;
   sensor reg PressLoaded;

   initial
      begin
	 PressMotor = Stop;
	 PressReadyToBeLoaded = N;
	 PressReadyToBeUnLoaded = N;
	 PressLoaded = N;
      end // initial
   
   always @(posedge clk) begin
      
      case (PressLoaded)
	Y: begin
	   case (PressPosition)
	     Top: begin
		PressMotor = GoDown;
	     end
	     Mid: begin
		if (PressMotor == GoDown) begin
		   PressMotor = Stop;
		   PressReadyToBeUnLoaded = Y;
		end // if (PressMotor == GoDown)
		else begin
		   PressMotor = GoDown;
		end // else: !if(PressMotor == GoDown)
	     end // case: Mid
	     Bot: begin
		if (ArmUnLoadedPress == Y && PressReadyToBeUnLoaded == Y) begin
		   PressMotor = GoUp;
		   PressLoaded = N;
		   PressReadyToBeUnLoaded = N;
		end // if (ArmUnLoadedPress == Y && PressReadyToBeUnLoaded == Y)
		else begin
		   PressMotor = Stop;
		end 
	     end // case: Bot
	   endcase // _case (PressPosition)
	end // case: Y
	N: begin
	   case (PressPosition)
	     Top: begin
		if (PressMotor == GoDown) begin
		   PressMotor = Stop;
		   PressReadyToBeLoaded = Y;
		end // if (PressMotor == GoDown)
		else begin
		   PressMotor = GoDown;
		end // else: !if(PressMotor == GoDown)
	     end // case: Top
	     Mid: begin
		if (ArmLoadedPress == Y) begin
		   PressLoaded = Y;
		   PressMotor = GoUp;
		end // if (ArmLoadedPress == Y)
		else begin
		   PressMotor = Stop;
		   PressReadyToBeLoaded = Y;
		end // else: !if(ArmLoadedPress == Y)
	     end // case: Mid
	   endcase // _case (PressPosition)
	end // case: N
	endcase // _case (PressLoaded)

      if (ArmLoadedPress == Y && PressReadyToBeLoaded == Y) begin
	 PressReadyToBeLoaded = N;
      end // if (ArmLoadedPress == N && PressReadyToBeLoaded == Y)

      if (PressReadyToBeUnLoaded == Y && ArmUnLoadedPress == Y) begin
	 PressReadyToBeUnLoaded = N;
      end // if (PressReadyToBeUnLoaded == Y && ArmUnLoadedPress == Y)
   end // always @ (posedge clk)

endmodule // PressCNTR

/*
 * 
 * ARM SET
 * 
 */
module ArmSet(clk, DBReady, PressReadyToBeUnLoaded, PressReadyToBeLoaded, 
	      RTOutReady, PieceOutArm, ArmUnLoadedPress, ArmLoadedPress, 
	      PieceGrabbedFromRT);
   input  clk; 
   input  DBReady;
   input  PressReadyToBeUnLoaded;
   input  PressReadyToBeLoaded;
   input  RTOutReady;
   output PieceOutArm;
   output ArmUnLoadedPress;
   output ArmLoadedPress;
   output PieceGrabbedFromRT;

   sensor wire DBReady;
   sensor wire PressReadyToBeUnLoaded;
   sensor wire PressReadyToBeLoaded;
   sensor wire RTOutReady;
   sensor wire PieceOutArm;
   sensor wire ArmUnLoadedPress;
   sensor wire ArmLoadedPress;
   sensor wire PieceGrabbedFromRT;
   
   sensor wire RALoadArmExtended; 
   sensor wire RALoadArmRetracted; 
   sensor wire RAUnLoadArmExtended; 
   sensor wire RAUnLoadArmRetracted; 
   sensor wire RAArmOverRT; 
   sensor wire RAArmOverUnLoadedPress; 
   sensor wire RAArmOverLoadedPress; 
   sensor wire RAArmOverDB;
   armHorizontalMovement wire RAExtendLoadArm; 
   armHorizontalMovement wire RAExtendUnLoadArm;
   armAngleMovement wire RARotaryMotor;

   RobotArm Arm(clk, RAExtendLoadArm, RAExtendUnLoadArm, RARotaryMotor,
		RALoadArmExtended, RALoadArmRetracted, RAUnLoadArmExtended,
		RAUnLoadArmRetracted, RAArmOverRT, RAArmOverUnLoadedPress, 
		RAArmOverLoadedPress, RAArmOverDB);

   RobotArmCNTR ACNTR(clk, RALoadArmExtended, RALoadArmRetracted, 
		      RAUnLoadArmExtended, RAUnLoadArmRetracted, RAArmOverRT, 
		      RAArmOverUnLoadedPress, RAArmOverLoadedPress, RAArmOverDB,
		      DBReady, PressReadyToBeUnLoaded, PressReadyToBeLoaded, 
		      RTOutReady, RAExtendLoadArm, RAExtendUnLoadArm, 
		      RARotaryMotor, PieceOutArm, ArmUnLoadedPress, 
		      ArmLoadedPress, PieceGrabbedFromRT);
endmodule // ArmSet

/*
 * 
 * ROBOT ARM
 * 
 */
module RobotArm(clk, RAExtendLoadArm, RAExtendUnLoadArm, RARotaryMotor,
		RALoadArmExtended, RALoadArmRetracted, RAUnLoadArmExtended,
		RAUnLoadArmRetracted, RAArmOverRT, RAArmOverUnLoadedPress, 
		RAArmOverLoadedPress, RAArmOverDB);
   input  clk; 
   input  RAExtendLoadArm;
   input  RAExtendUnLoadArm;
   input  RARotaryMotor;
   output RALoadArmExtended;
   output RALoadArmRetracted;
   output RAUnLoadArmExtended;
   output RAUnLoadArmRetracted;
   output RAArmOverRT;
   output RAArmOverUnLoadedPress;
   output RAArmOverLoadedPress;
   output RAArmOverDB;
   
   armHorizontalMovement wire RAExtendLoadArm;
   armHorizontalMovement wire RAExtendUnLoadArm;
   armAngleMovement wire RARotaryMotor;
   sensor wire RALoadArmExtended;
   sensor wire RALoadArmRetracted;
   sensor wire RAUnLoadArmExtended;
   sensor wire RAUnLoadArmRetracted;
   sensor wire RAArmOverRT;
   sensor wire RAArmOverUnLoadedPress;
   sensor wire RAArmOverLoadedPress;
   sensor wire RAArmOverDB;
   
   armPosition reg RALoadArm;
   armPosition reg RAUnLoadArm;
   armAnglePosition reg RAAnglePos; 

   assign RALoadArmExtended = (RALoadArm == Extended) ? Y : N;
   assign RALoadArmRetracted = (RALoadArm == Retracted) ? Y : N;
   assign RAUnLoadArmExtended = (RAUnLoadArm == Extended) ? Y : N;
   assign RAUnLoadArmRetracted = (RAUnLoadArm == Retracted) ? Y : N; 
   assign RAArmOverRT = (RAAnglePos == OverRT) ? Y : N;
   assign RAArmOverUnLoadedPress = (RAAnglePos == OverUnLoadedPress) ? Y : N;
   assign RAArmOverLoadedPress = (RAAnglePos == OverLoadedPress) ? Y : N; 
   assign RAArmOverDB = (RAAnglePos == OverDB) ? Y : N;
   
   initial
      begin
	 RALoadArm = Retracted;
	 RAUnLoadArm = Retracted;
	 RAAnglePos = $ND(OverRT, OverLoadedPress, OverDB, OverUnLoadedPress);
      end // initial

   always @(posedge clk) begin

      /* Control the horizontal movement of the load arm */
      if (RAExtendLoadArm == Extend) begin
	 case (RALoadArm)
	   Retracted: begin
	      RALoadArm = Middle;
	   end // case: Retracted
	   Middle: begin
	      RALoadArm = Extended;
	   end // if (RALoadArm = Middle)
	 endcase // _case (RALoadArm)
      end // if (RAExtendLoadArm == Extend)

      if (RAExtendLoadArm == Retract) begin
	 case (RALoadArm)
	   Extended: begin
	      RALoadArm = Middle;
	   end // case: Extended
	   Middle: begin
	      RALoadArm = Retracted;
	   end // if (RALoadArm == Middle)
	 endcase // _case (RALoadArm)
      end // if (RAExtendLoadArm = Retract)
      
      /* Control the horizontal movement of the UnLoad arm */
      if (RAExtendUnLoadArm == Extend) begin
	 case (RAUnLoadArm)
	   Retracted: begin
	      RAUnLoadArm = Middle;
	   end // case: Retracted
	   Middle: begin
	      RAUnLoadArm = Extended;
	   end // case: Middle
	 endcase // _case (RAUnLoadArm)
      end // if (RAExtendLoadArm == Extend)

      if (RAExtendUnLoadArm == Retract) begin
	 case (RAUnLoadArm)
	   Extended: begin
	      RAUnLoadArm = Middle;
	   end // case: Extended
	   Middle: begin
	      RAUnLoadArm = Retracted;
	   end // case: Middle
	 endcase // _case (RAUnLoadArm)
      end // if (RAExtendUnLoadArm = Retract)
      
      /* Control the rotation of the arm */
      if (RARotaryMotor == CCWise) begin
	 case (RAAnglePos)
	   OverRT: begin
	      RAAnglePos = OverUnLoadedPress;
	   end // case: OverRT
	   OverUnLoadedPress: begin
	      RAAnglePos = OverDB;
	   end // case: OverLoadedPress
	   OverDB: begin
	      RAAnglePos = OverLoadedPress;
	   end // case: OverDB
	 endcase // _case (RAAnglePos)
      end // if (RARotaryMotor == CCWise)
      if (RARotaryMotor == CWise) begin
	 case (RAAnglePos)
	   OverLoadedPress: begin
	      RAAnglePos = OverDB;
	   end // case: OverLoadedPress
	   OverDB: begin
	      RAAnglePos = OverUnLoadedPress;
	   end // case: OverDB
	   OverUnLoadedPress: begin
	      RAAnglePos = OverRT;
	   end // case: OverUnLoadedPress
	 endcase // _case (RAAnglePos)
      end // if (RARotaryMotor == CWise)
   end // always @ (posedge clk)
endmodule // RobotArm

/*
 * 
 * ROBOT ARM CNTR
 * 
 */
module RobotArmCNTR(clk, RALoadArmExtended, RALoadArmRetracted, 
		    RAUnLoadArmExtended, RAUnLoadArmRetracted, RAArmOverRT, 
		    RAArmOverUnLoadedPress, RAArmOverLoadedPress, RAArmOverDB,
		    DBReady, PressReadyToBeUnLoaded, PressReadyToBeLoaded, 
		    RTOutReady, RAExtendLoadArm, RAExtendUnLoadArm, 
		    RARotaryMotor, PieceOutArm, ArmUnLoadedPress, 
		    ArmLoadedPress, PieceGrabbedFromRT);
   input  clk;
   input  RALoadArmExtended;
   input  RALoadArmRetracted;
   input  RAUnLoadArmExtended;
   input  RAUnLoadArmRetracted;
   input  RAArmOverRT;
   input  RAArmOverUnLoadedPress;
   input  RAArmOverLoadedPress;
   input  RAArmOverDB;
   input  DBReady;
   input  PressReadyToBeUnLoaded;
   input  PressReadyToBeLoaded;
   input  RTOutReady;
   output RAExtendLoadArm;
   output RAExtendUnLoadArm;
   output RARotaryMotor;
   output PieceOutArm;
   output ArmUnLoadedPress;
   output ArmLoadedPress;
   output PieceGrabbedFromRT;
   
   sensor wire DBReady;
   sensor wire PressReadyToBeUnLoaded;
   sensor wire PressReadyToBeLoaded;
   sensor wire RTOutReady;
   sensor wire RALoadArmExtended;
   sensor wire RALoadArmRetracted;
   sensor wire RAUnLoadArmExtended;
   sensor wire RAUnLoadArmRetracted;
   sensor wire RAArmOverRT;
   sensor wire RAArmOverUnLoadedPress;
   sensor wire RAArmOverLoadedPress;
   sensor wire RAArmOverDB;
   
   armHorizontalMovement reg RAExtendLoadArm;
   armHorizontalMovement reg RAExtendUnLoadArm;
   armAngleMovement reg RARotaryMotor;
   sensor reg PieceOutArm;
   sensor reg ArmUnLoadedPress;
   sensor reg ArmLoadedPress;
   sensor reg PieceGrabbedFromRT;
   sensor reg LoadArmLoaded;
   sensor reg UnLoadArmLoaded;

   initial
      begin
	 RAExtendLoadArm = Stop;
	 RAExtendUnLoadArm = Stop;
	 RARotaryMotor = Stop;
	 PieceOutArm = N;
	 ArmUnLoadedPress = N;
	 ArmLoadedPress = N;
	 PieceGrabbedFromRT = N;
	 LoadArmLoaded = N;
	 UnLoadArmLoaded = N;
      end // initial

   always @(posedge clk) begin
      if (LoadArmLoaded == N && UnLoadArmLoaded == N) begin
	 if (RAArmOverRT == Y) begin
	    if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y &&
		RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
	       RARotaryMotor == Stop) begin
	       if (RTOutReady == Y) begin
		  RAExtendLoadArm = Extend;
	       end // if (RTOutReady == Y)
	       else begin
		  if (PressReadyToBeUnLoaded == Y) begin
		     RARotaryMotor = CCWise;
		  end // if (PressReadyToBeUnLoaded == Y)
	       end // else: !if(RTOutReady == Y)
	    end 
	    else begin
	       if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y &&
		   RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
		   RARotaryMotor == CCWise) begin
		      if (PressReadyToBeUnLoaded == Y) begin
			 RARotaryMotor = Stop;
			 RAExtendUnLoadArm = Extend;
		      end // if (PressReadyToBeUnLoaded == Y)
		   end 
	       else begin
		  if (RALoadArmRetracted == N && RALoadArmExtended == N &&
		      RAUnLoadArmRetracted == Y && 
		      RAExtendLoadArm == Extend && RAExtendUnLoadArm == Stop &&
		      RARotaryMotor == Stop) begin
			 RAExtendLoadArm = Retract;
			 LoadArmLoaded = Y;
			 PieceGrabbedFromRT = Y;
		      end 
	       end 
	    end // else: !if(RALoadArmRetracted == Y &&...
	 end // if (RAArmOverRT == Y)
	 if (RAArmOverLoadedPress == Y) begin
	    if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y &&
		RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
		RARotaryMotor == Stop) begin
	       RARotaryMotor = CWise;
	    end // if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y ...
	    if (RALoadArmRetracted == N && RALoadArmExtended == N &&
		RAUnLoadArmRetracted == Y && 
		RAExtendLoadArm == Retract && RAExtendUnLoadArm == Stop &&
		RARotaryMotor == Stop) begin
	       RAExtendLoadArm = Stop;
	       RARotaryMotor = CWise;
	    end // if (RALoadArmRetracted == N && RALoadArmExtended == N &&...
	 end // if (RAArmOverLoadedPress == Y)
	 if (RAArmOverUnLoadedPress == Y) begin
	    if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y &&
		RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
	       RARotaryMotor == Stop) begin
	       if (PressReadyToBeUnLoaded == Y) begin
		  RAExtendLoadArm = Extend;
	       end // if (RTOutReady == Y)
	       else begin
		  RARotaryMotor = CWise;
	       end // else: !if(RTOutReady == Y)
	    end // if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y ...
	    else begin
	       if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y && 
		   RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
		   RARotaryMotor == CWise) begin
		      RARotaryMotor = Stop;
		      if (RTOutReady == Y) begin
			 RAExtendLoadArm = Extend;
		      end // if (RTOutReady == Y)
		   end // if (RALoadArmRetracted == Y && ...
	       else begin
		  if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == N && 
		      RAUnLoadArmExtended == N && 
		      RAExtendLoadArm == Stop && RAExtendUnLoadArm == Extend &&
		      RARotaryMotor == Stop) begin
			 RAExtendUnLoadArm = Retract;
			 ArmUnLoadedPress = Y;
			 UnLoadArmLoaded = Y;
		      end // if (RALoadArmRetracted == Y && RAUnLoadArmRetr...
	       end // else: !if(RALoadArmRetracted == Y && RAUnLoadArmRetr...
	    end // else: !if(RALoadArmRetracted == Y && RAUnLoadArmRetract...
	 end // if (RAArmOverUnLoadedPress == Y)
	 if (RAArmOverDB == Y) begin
	    if (RALoadArmRetracted == Y && RAUnLoadArmRetracted == Y &&
		RAExtendLoadArm == Stop && RAExtendUnLoadArm == Stop &&
		RARotaryMotor == Stop) begin
	       RARotaryMotor = CWise;
	    end // if (RALoadArmRetracted == Y && RAUnLoadArmRetracte...
	    if (RAExtendUnLoadArm == Retract && RAUnLoadArmExtended == N && 
		RAUnLoadArmRetracted == N) begin
	       RAExtendUnLoadArm = Stop;
	       RARotaryMotor = CWise;
	    end 
	    if (RARotaryMotor == CWise) begin
	       if (PressReadyToBeUnLoaded == Y && RTOutReady == N) begin
		  RARotaryMotor = Stop;
		  RAExtendUnLoadArm = Extend;
	       end // if (PressReadyToBeUnLoaded == Y && RTOutReady == N)
	    end // if (RARotaryMotor == CWise)
	 end // if (RAArmOverDB == Y)
      end // if (LoadArmLoaded == N && UnLoadArmLoaded == N)
      else begin
	 if (LoadArmLoaded == N && UnLoadArmLoaded == Y) begin
	    if (RAArmOverUnLoadedPress == Y) begin
	       if (RARotaryMotor == CCWise) begin
		  RARotaryMotor = Stop;
		  RAExtendUnLoadArm = Extend;
	       end // if (RARotaryMotor == CCWise)
	       if (RAExtendUnLoadArm == Retract && RAUnLoadArmExtended == N 
		   && RAUnLoadArmRetracted == N) begin
		  RAExtendUnLoadArm = Stop;
		  RARotaryMotor = CCWise;
	       end 
	    end // if (RAArmOverUnloadedPress == Y)
	    if (RAArmOverDB == Y) begin
	       if (RAExtendUnLoadArm == Extend && RAUnLoadArmExtended == N && 
		   RAUnLoadArmRetracted == N) begin
		  if (DBReady == Y) begin
		     RAExtendUnLoadArm = Retract;
		     PieceOutArm = Y;
		     UnLoadArmLoaded = N;
		  end // if (DBReady == Y)
		  else begin
		     RAExtendUnLoadArm = Stop;
		  end // else: !if(DBReady == Y)
	       end 
	       if (RAExtendUnLoadArm == Stop && RAUnLoadArmExtended == Y && 
		   DBReady == Y) begin
		  RAExtendUnLoadArm = Retract;
		  PieceOutArm = Y;
		  UnLoadArmLoaded = N;
	       end 
	    end // if (RAArmOverDB == Y)
	 end // if (LoadArmLoaded == N && UnLoadArmLoaded == Y)
	 else begin
	    if (LoadArmLoaded == Y && UnLoadArmLoaded == N) begin
	       if (RAArmOverRT == Y) begin
		  if (RARotaryMotor == CCWise) begin
		     if (PressReadyToBeUnLoaded == Y) begin
			RARotaryMotor = Stop;
			RAExtendUnLoadArm = Extend;
		     end // if (PressReadyToBeUnLoaded == Y)
		  end // if (RARotaryMotor == CCWise)
		  if (RAExtendLoadArm == Retract && RALoadArmExtended == N && 
		      RALoadArmRetracted == N) begin
		     RAExtendLoadArm = Stop;
		     RARotaryMotor = CCWise;
		  end 
	       end // if (RAArmOverUnLoadedPress == Y)
	       if (RAArmOverLoadedPress == Y) begin
                  if (RAExtendLoadArm == Stop && RARotaryMotor == Stop && 
		      RAExtendUnLoadArm == Stop &&
		      RALoadArmRetracted == Y) begin
		     if (PressReadyToBeLoaded == Y) begin
			RAExtendLoadArm = Extend;
		     end // if (PressReadyToBeLoaded == Y)
		  end 
		  if (RAExtendLoadArm == Extend && RALoadArmExtended == N && 
		      RALoadArmRetracted == N) begin
		     RAExtendLoadArm = Retract;
		     ArmLoadedPress = Y;
		     LoadArmLoaded = N;
		  end 
	       end // if (RAArmOverLoadedPress == Y)
	       if (RAArmOverUnLoadedPress == Y) begin
		  if (RAExtendUnLoadArm == Extend && RAUnLoadArmExtended == N 
		      && RAUnLoadArmRetracted == N) begin
		     if (PressReadyToBeUnLoaded == Y) begin
			ArmUnLoadedPress = Y;
			UnLoadArmLoaded = Y;
			RAExtendUnLoadArm = Retract;
		     end // if (PressReadyToBeUnLoaded == Y)
		  end 
	       end // if (RAArmOverUnLoadedPress == Y)
	       if (RAArmOverDB == Y) begin
		  if (RARotaryMotor == CCWise) begin
		     RARotaryMotor = Stop;
		     if (PressReadyToBeLoaded == Y) begin
			RAExtendLoadArm = Extend;
		     end // if (PressReadyToBeLoaded == Y)
		  end // if (RARotaryMotor == CCWise)
		  if (RAExtendUnLoadArm == Retract && RAUnLoadArmExtended == N 
		      && RAUnLoadArmRetracted == N)begin
		     RAExtendUnLoadArm = Stop;
		     RARotaryMotor = CCWise;
		  end 
	       end // if (RAArmOverDB == Y)
	    end // if (LoadArmLoaded == Y && UnLoadArmLoaded == N)
	    else begin
	       if (LoadArmLoaded == Y && UnLoadArmLoaded == Y) begin
		  if (RAArmOverUnLoadedPress == Y) begin
		     if (RARotaryMotor == CCWise) begin
			RARotaryMotor = Stop;
			RAExtendUnLoadArm = Extend;
		     end // if (RARotaryMotor = CCWise)
		     if (RAExtendUnLoadArm == Retract && 
			 RAUnLoadArmExtended == N && 
			 RAUnLoadArmRetracted == N) begin
			RAExtendUnLoadArm = Stop;
			RARotaryMotor = CCWise;
		     end 
		  end // if (RAArmOverUnLoadedPress == Y)
		  if (RAArmOverDB == Y) begin
		     if (RAExtendUnLoadArm == Extend && RAUnLoadArmExtended == N
			 && RAUnLoadArmRetracted == N) begin
			RAExtendUnLoadArm = Stop;
			if (DBReady == Y) begin
			   RAExtendUnLoadArm = Retract;
			   UnLoadArmLoaded = N;
			   PieceOutArm = Y;
			end // if (DBReady == Y)
		     end 
		     if (RAExtendUnLoadArm == Stop && 
			 RAUnLoadArmExtended == Y) begin
			if (DBReady == Y) begin
			   RAExtendUnLoadArm = Retract;
			   UnLoadArmLoaded = N;
			   PieceOutArm = Y;
			end // if (DBReady == Y)
		     end 
		  end // if (RAArmOverDB == Y)
	       end // if (LoadArmLoaded == Y && UnLoadArmLoaded == Y)

	    end // else: !if(LoadArmLoaded == Y && UnLoadArmLoaded == N)
	 end // else: !if(LoadArmLoaded == N && UnLoadArmLoaded == Y)
      end // else: !if(LoadArmLoaded == N && UnLoadArmLoaded == N)
      
      if (DBReady == N && PieceOutArm == Y) begin
	 PieceOutArm = N;
      end // if (DBReady == N && PieceOutArm == Y)
      
      if (PressReadyToBeUnLoaded == N && ArmUnLoadedPress == Y) begin
	 ArmUnLoadedPress = N;
      end // if (PressReadyToBeUnLoaded == N && ArmUnLoadedPress == Y)
      
      if (ArmLoadedPress == Y && PressReadyToBeLoaded == N) begin
	 ArmLoadedPress = N;
      end // if (ArmLoadedPress == Y && PieceRecivedOnPress == N)
      
      if (RTOutReady == N && PieceGrabbedFromRT == Y) begin
	 PieceGrabbedFromRT = N;
      end // if (RTOutReady == N && PieceGrabbedFromRT == Y)
   end // always @ (posedge clk)
endmodule // RobotArmCNTR
