#
# Specification for the ProductionCell 
#
# WARNING: All the formulas are included here, but only a few of them are used
# for the check_script
#
# Author: Abelardo Pardo <abel@vlsi.colorado.edu>
#
# Limitation of Mobility for the Travelling Crane
#
AG(!((TC.Crane.HorizontalPos = OverFB) * 
     (TC.CraneCNTR.HorizontalMove = GoLeft)));                            # 1
AG(!((TC.Crane.HorizontalPos = OverDB) * 
     (TC.CraneCNTR.HorizontalMove = GoRight)));                           # 2
AG(!((TC.Crane.HorizontalPos = Middle) * 
    (!(TC.Crane.VerticalPos = UpMost))));                                 # 3
AG(!((TC.Crane.HorizontalPos = OverDB) * 
     (TC.Crane.VerticalPos = FBHight)));                                  # 4
AG(!(TC.Crane.VerticalPos = UpMost * 
     TC.CraneCNTR.VerticalMove = GoUp));                                  # 5
AG(!(TC.Crane.VerticalPos = FBHight * 
    TC.CraneCNTR.VerticalMove = GoDown));                                 # 6
AG(!TC.Crane.HorizontalMove = Stop -> 
    TC.CraneCNTR.VerticalPos = UpMost);                                   # 7
#
# Limitation of Mobility of the Rotary Table
#
#AG(!(RT.RTable.RTAngle = S * 
#     RT.RTableCNTR.RTRotaryMotor = CWise));                               # 8
#AG(!(RT.RTable.RTAngle = SE * 
#     RT.RTableCNTR.RTRotaryMotor = CCWise));                              # 9
#AG(!(RT.RTable.RTHight = Top * 
#     RT.RTableCNTR.RTVerticalMotor = GoUp));                              # 10
#AG(!(RT.RTable.RTHight = Bot * 
#     RT.RTableCNTR.RTVerticalMotor = GoDown));                            # 11
#
# Limitation of Mobility of the Rotary Arm
#
#AG(!(AR.Arm.RAAnglePos = OverRT * 
#     AR.ACNTR.RARotaryMotor = CWise));                                    # 12
#AG(!(AR.Arm.RAAnglePos = OverLoadedPress * 
#     AR.ACNTR.RARotaryMotor = CCWise));                                   # 13
#AG(!(AR.Arm.RALoadArm = Retracted * 
#     AR.ACNTR.RAExtendLoadArm = Retract));                                # 14
#AG(!(AR.Arm.RALoadArm = Extended * 
#     AR.ACNTR.RAExtendLoadArm = Extend));                                 # 15
#AG(!(AR.Arm.RAUnLoadArm = Retracted * 
#     AR.ACNTR.RAExtendUnLoadArm = Retract));                              # 16
#AG(!(AR.Arm.RAUnLoadArm = Extended * 
#     AR.ACNTR.RAExtendUnLoadArm = Extend));                               # 17
#AG(!((!AR.Arm.RALoadArm = Retracted) * 
#     (!AR.Arm.RAUnLoadArm = Retracted)));                                 # 18
#AG(!AR.ACNTR.RARotaryMotor = Stop -> 
#    (AR.Arm.RALoadArm = Retracted * AR.Arm.RAUnLoadArm = Retracted));     # 19
#
# Limitation of Mobility for the Press
#
#AG(!(PR.Pr.PressPosition = Top * PR.PrCNTR.PressMotor = GoUp));           # 20
#AG(!(PR.Pr.PressPosition = Bot * PR.PrCNTR.PressMotor = GoDown));         # 21
#AG((AR.Arm.RAAnglePos = OverLoadedPress * !AR.Arm.RALoadArm = Retracted)
#   -> PR.Pr.PressPosition = Mid);                                         # 22
AG(!(PR.Pr.PressPosition = Mid * AR.Arm.RALoadArm = Extended * 
     AR.Arm.RAAnglePos = OverLoadedPress));                               # 23
AG(!(PR.Pr.PressPosition = Top * AR.Arm.RAUnLoadArm = Extended * 
     AR.Arm.RAAnglePos = OverUnLoadedPress));                             # 24
#
# Correctness of the communication protocol DB -> Crane
#
AG(DB.DBeltCNTR.PieceOutDB = Y -> 
   AF(TC.CraneCNTR.PieceGrabbedFromDB = Y));                              # 25
#AG(DB.DBeltCNTR.PieceOutDB = Y -> 
#   A(DB.DBeltCNTR.PieceOutDB = Y U TC.CraneCNTR.PieceGrabbedFromDB = Y)); # 26
#
# Correctness of the communication protocol ARM -> DB
#
#AG(DB.DBeltCNTR.DBReady = Y -> AF(AR.ACNTR.PieceOutArm = Y));             # 27
#AG(DB.DBeltCNTR.DBReady = Y ->
#   A(DB.DBeltCNTR.DBReady = Y U AR.ACNTR.PieceOutArm = Y));               # 28
#
# Correctness of the communication protocol RT -> Arm
#
#AG(RT.RTableCNTR.RTOutReady = Y -> 
#  (AF(AR.ACNTR.PieceGrabbedFromRT = Y)));                                 # 29
#AG(RT.RTableCNTR.RTOutReady = Y -> 
#  A(RT.RTableCNTR.RTOutReady = Y U AR.ACNTR.PieceGrabbedFromRT = Y));     # 30
#
# Correctness of the communication protocol FB -> Crane
#
#AG(FB.FBeltCNTR.FBReady = Y -> AF(TC.CraneCNTR.PieceReleasedOnFB = Y));   # 31
#AG(FB.FBeltCNTR.FBReady = Y ->
#  A(FB.FBeltCNTR.FBReady = Y U TC.CraneCNTR.PieceReleasedOnFB = Y));      # 32
#
# Correctness of the communication protocol FB -> RT (2358 seconds)
#
#AG(FB.FBeltCNTR.PieceOutFB = Y -> 
#  AF(RT.RTableCNTR.PieceGrabbedFromFB = Y));                              # 33
#AG(FB.FBeltCNTR.PieceOutFB = Y ->
#  A(FB.FBeltCNTR.PieceOutFB = Y U RT.RTableCNTR.PieceGrabbedFromFB = Y)); # 34
#
# Correctness of the communication protocol ARM -> LOAD PRESS
#
#AG( PR.PrCNTR.PressReadyToBeLoaded = Y -> 
#    AF(AR.ACNTR.ArmLoadedPress = Y));                                     # 35
#AG(PR.PrCNTR.PressReadyToBeLoaded = Y ->
#  A(PR.PrCNTR.PressReadyToBeLoaded = Y U AR.ACNTR.ArmLoadedPress = Y));   # 36
#
# Correctness of the communication protocol ARM -> UNLOAD PRESS
#
#AG( PR.PrCNTR.PressReadyToBeUnLoaded = Y -> 
#    AF(AR.ACNTR.ArmUnLoadedPress = Y));                                   # 37
#AG(PR.PrCNTR.PressReadyToBeUnLoaded = Y ->
#  A(PR.PrCNTR.PressReadyToBeUnLoaded = Y U 
#    AR.ACNTR.ArmUnLoadedPress = Y));                                      # 38
