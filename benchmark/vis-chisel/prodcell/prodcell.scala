package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
class ProductionCellEnums {
  // Sensor states
  val sensorY = 1.U(1.W)
  val sensorN = 0.U(1.W)
  
  // Switch states
  val switchOn = 1.U(1.W)
  val switchOff = 0.U(1.W)
  
  // Crane horizontal movement
  val craneGoLeft = 0.U(2.W)
  val craneGoRight = 1.U(2.W)
  val craneStop = 2.U(2.W)
  
  // Crane vertical movement
  val craneGoUp = 0.U(2.W)
  val craneGoDown = 1.U(2.W)
  val craneStopV = 2.U(2.W)
  
  // Crane grip
  val craneGrab = 1.U(1.W)
  val craneFree = 0.U(1.W)
  
  // TC horizontal position
  val tcOverFB = 0.U(2.W)
  val tcMiddle = 1.U(2.W)
  val tcOverDB = 2.U(2.W)
  
  // TC vertical position
  val tcUpMost = 0.U(2.W)
  val tcDBHight = 1.U(2.W)
  val tcFBHight = 2.U(2.W)
  
  // Unit in belt
  val unitE = 0.U(1.W)
  val unitF = 1.U(1.W)
  
  // RT angle position
  val rtS = 0.U(2.W)
  val rtSSE = 1.U(2.W)
  val rtSE = 2.U(2.W)
  
  // RT vertical position
  val rtTop = 0.U(2.W)
  val rtMid = 1.U(2.W)
  val rtBot = 2.U(2.W)
  
  // RT angle movement
  val rtCWise = 0.U(2.W)
  val rtStop = 1.U(2.W)
  val rtCCWise = 2.U(2.W)
  
  // RT vertical movement
  val rtGoUp = 0.U(2.W)
  val rtGoDown = 1.U(2.W)
  val rtStopV = 2.U(2.W)
  
  // Press vertical movement
  val pressGoUp = 0.U(2.W)
  val pressGoDown = 1.U(2.W)
  val pressStop = 2.U(2.W)
  
  // Press vertical position
  val pressTop = 0.U(2.W)
  val pressMid = 1.U(2.W)
  val pressBot = 2.U(2.W)
  
  // Arm horizontal movement
  val armExtend = 0.U(2.W)
  val armRetract = 1.U(2.W)
  val armStopH = 2.U(2.W)
  
  // Arm angle position
  val armOverRT = 0.U(3.W)
  val armOverLoadedPress = 1.U(3.W)
  val armOverDB = 2.U(3.W)
  val armOverUnLoadedPress = 3.U(3.W)
  
  // Arm angle movement
  val armCWise = 0.U(2.W)
  val armStopA = 1.U(2.W)
  val armCCWise = 2.U(2.W)
  
  // Arm position
  val armExtended = 0.U(2.W)
  val armRetracted = 1.U(2.W)
  val armMiddle = 2.U(2.W)
}

// Production Cell Top Module
class ProductionCell extends Module {
  val io = IO(new Bundle {
    // Internal signals exposed for verification
    val PieceOutDB = Output(Bool())
    val PieceOutFB = Output(Bool())
    val PieceGrabbedFromDB = Output(Bool())
    val PieceGrabbedFromRT = Output(Bool())
    val PieceGrabbedFromFB = Output(Bool())
    val FBReady = Output(Bool())
    val PieceOutArm = Output(Bool())
    val PieceReleasedOnFB = Output(Bool())
    val DBReady = Output(Bool())
    val ArmUnLoadedPress = Output(Bool())
    val PressReadyToBeUnLoaded = Output(Bool())
    val ArmLoadedPress = Output(Bool())
    val PressReadyToBeLoaded = Output(Bool())
    val RTOutReady = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  // Instantiate subsystems
  val tc = Module(new TravellingCraneSet())
  val db = Module(new DepositBeltSet())
  val fb = Module(new FeedBeltSet())
  val rt = Module(new RotaryTableSet())
  val pr = Module(new PressSet())
  val ar = Module(new ArmSet())
  
  // Connect TravellingCraneSet
  tc.io.FBReady := fb.io.FBReady
  tc.io.PieceOutDB := db.io.PieceOutDB
  io.PieceGrabbedFromDB := tc.io.PieceGrabbedFromDB
  io.PieceReleasedOnFB := tc.io.PieceReleasedOnFB
  
  // Connect DepositBeltSet
  db.io.PieceGrabbedFromDB := tc.io.PieceGrabbedFromDB
  db.io.PieceOutArm := ar.io.PieceOutArm
  io.PieceOutDB := db.io.PieceOutDB
  io.DBReady := db.io.DBReady
  
  // Connect FeedBeltSet
  fb.io.PieceGrabbedFromFB := rt.io.PieceGrabbedFromFB
  fb.io.PieceReleasedOnFB := tc.io.PieceReleasedOnFB
  io.FBReady := fb.io.FBReady
  io.PieceOutFB := fb.io.PieceOutFB
  
  // Connect RotaryTableSet
  rt.io.PieceOutFB := fb.io.PieceOutFB
  rt.io.PieceGrabbedFromRT := ar.io.PieceGrabbedFromRT
  io.PieceGrabbedFromFB := rt.io.PieceGrabbedFromFB
  io.RTOutReady := rt.io.RTOutReady
  
  // Connect PressSet
  pr.io.ArmLoadedPress := ar.io.ArmLoadedPress
  pr.io.ArmUnLoadedPress := ar.io.ArmUnLoadedPress
  io.PressReadyToBeLoaded := pr.io.PressReadyToBeLoaded
  io.PressReadyToBeUnLoaded := pr.io.PressReadyToBeUnLoaded
  
  // Connect ArmSet
  ar.io.DBReady := db.io.DBReady
  ar.io.PressReadyToBeUnLoaded := pr.io.PressReadyToBeUnLoaded
  ar.io.PressReadyToBeLoaded := pr.io.PressReadyToBeLoaded
  ar.io.RTOutReady := rt.io.RTOutReady
  io.PieceOutArm := ar.io.PieceOutArm
  io.ArmUnLoadedPress := ar.io.ArmUnLoadedPress
  io.ArmLoadedPress := ar.io.ArmLoadedPress
  io.PieceGrabbedFromRT := ar.io.PieceGrabbedFromRT
}

// Travelling Crane Set
class TravellingCraneSet extends Module {
  val io = IO(new Bundle {
    val FBReady = Input(Bool())
    val PieceOutDB = Input(Bool())
    val PieceGrabbedFromDB = Output(Bool())
    val PieceReleasedOnFB = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val crane = Module(new TravellingCrane())
  val controller = Module(new TravellingCraneCNTR())
  
  // Connect crane to controller
  crane.io.HorizontalMove := controller.io.HorizontalMove
  crane.io.VerticalMove := controller.io.VerticalMove
  controller.io.CraneOnTheLeft := crane.io.CraneOnTheLeft
  controller.io.CraneOnTheRight := crane.io.CraneOnTheRight
  controller.io.VerticalPos := crane.io.VerticalPos
  
  // External connections
  controller.io.FBReady := io.FBReady
  controller.io.PieceOutDB := io.PieceOutDB
  io.PieceGrabbedFromDB := controller.io.PieceGrabbedFromDB
  io.PieceReleasedOnFB := controller.io.PieceReleasedOnFB
}

// Travelling Crane
class TravellingCrane extends Module {
  val io = IO(new Bundle {
    val HorizontalMove = Input(UInt(2.W))
    val VerticalMove = Input(UInt(2.W))
    val CraneOnTheLeft = Output(Bool())
    val CraneOnTheRight = Output(Bool())
    val VerticalPos = Output(UInt(2.W))
  })
  
  val enums = new ProductionCellEnums()
  
  val horizontalPos = RegInit(enums.tcMiddle)
  val verticalPos = RegInit(enums.tcUpMost)
  
  // Position sensors
  io.CraneOnTheLeft := (horizontalPos === enums.tcOverFB)
  io.CraneOnTheRight := (horizontalPos === enums.tcOverDB)
  io.VerticalPos := verticalPos
  
  // Horizontal movement logic
  when(io.HorizontalMove === enums.craneGoLeft) {
    when(horizontalPos === enums.tcMiddle) {
      horizontalPos := enums.tcOverFB
    }.elsewhen(horizontalPos === enums.tcOverDB) {
      horizontalPos := enums.tcMiddle
    }
  }
  
  when(io.HorizontalMove === enums.craneGoRight) {
    when(horizontalPos === enums.tcMiddle) {
      horizontalPos := enums.tcOverDB
    }.elsewhen(horizontalPos === enums.tcOverFB) {
      horizontalPos := enums.tcMiddle
    }
  }
  
  // Vertical movement logic
  when(io.VerticalMove === enums.craneGoUp) {
    when(verticalPos === enums.tcDBHight) {
      verticalPos := enums.tcUpMost
    }.elsewhen(verticalPos === enums.tcFBHight) {
      verticalPos := enums.tcDBHight
    }
  }
  
  when(io.VerticalMove === enums.craneGoDown) {
    when(verticalPos === enums.tcUpMost) {
      verticalPos := enums.tcDBHight
    }.elsewhen(verticalPos === enums.tcDBHight) {
      verticalPos := enums.tcFBHight
    }
  }
}

// Travelling Crane Controller
class TravellingCraneCNTR extends Module {
  val io = IO(new Bundle {
    val FBReady = Input(Bool())
    val PieceOutDB = Input(Bool())
    val CraneOnTheLeft = Input(Bool())
    val CraneOnTheRight = Input(Bool())
    val VerticalPos = Input(UInt(2.W))
    val HorizontalMove = Output(UInt(2.W))
    val VerticalMove = Output(UInt(2.W))
    val PieceReleasedOnFB = Output(Bool())
    val PieceGrabbedFromDB = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val horizontalMove = RegInit(enums.craneStop)
  val verticalMove = RegInit(enums.craneStopV)
  val grip = RegInit(enums.craneFree)
  val pieceReleasedOnFB = RegInit(false.B)
  val pieceGrabbedFromDB = RegInit(false.B)
  
  io.HorizontalMove := horizontalMove
  io.VerticalMove := verticalMove
  io.PieceReleasedOnFB := pieceReleasedOnFB
  io.PieceGrabbedFromDB := pieceGrabbedFromDB
  
  // Complex state machine logic
  when(grip === enums.craneGrab) {
    when(io.VerticalPos === enums.tcUpMost) {
      when(io.CraneOnTheLeft) {
        horizontalMove := enums.craneStop
        verticalMove := enums.craneGoDown
      }.otherwise {
        when(!io.CraneOnTheRight && !io.CraneOnTheLeft) {
          when(horizontalMove === enums.craneGoLeft) {
            horizontalMove := enums.craneStop
            verticalMove := enums.craneGoDown
          }.otherwise {
            horizontalMove := enums.craneGoLeft
            verticalMove := enums.craneStopV
          }
        }.otherwise {
          horizontalMove := enums.craneGoLeft
          verticalMove := enums.craneStopV
        }
      }
    }.elsewhen(io.VerticalPos === enums.tcDBHight) {
      when(io.CraneOnTheLeft) {
        when(verticalMove === enums.craneGoDown) {
          when(io.FBReady) {
            grip := enums.craneFree
            horizontalMove := enums.craneStop
            verticalMove := enums.craneGoUp
            pieceReleasedOnFB := true.B
          }.otherwise {
            horizontalMove := enums.craneStop
            verticalMove := enums.craneStopV
          }
        }.otherwise {
          verticalMove := enums.craneGoDown
        }
      }
      when(io.CraneOnTheRight) {
        when(verticalMove === enums.craneGoUp) {
          horizontalMove := enums.craneGoLeft
          verticalMove := enums.craneStopV
        }.otherwise {
          verticalMove := enums.craneGoUp
        }
      }
    }.elsewhen(io.VerticalPos === enums.tcFBHight) {
      when(io.CraneOnTheLeft) {
        when(io.FBReady) {
          grip := enums.craneFree
          horizontalMove := enums.craneStop
          verticalMove := enums.craneGoUp
          pieceReleasedOnFB := true.B
        }.otherwise {
          horizontalMove := enums.craneStop
          verticalMove := enums.craneStopV
        }
      }
    }
  }.otherwise { // grip === Free
    when(io.VerticalPos === enums.tcUpMost) {
      when(io.CraneOnTheLeft) {
        horizontalMove := enums.craneGoRight
        verticalMove := enums.craneStopV
      }.otherwise {
        when(!io.CraneOnTheRight) {
          when(horizontalMove === enums.craneGoRight) {
            horizontalMove := enums.craneStop
            verticalMove := enums.craneGoDown
          }.otherwise {
            horizontalMove := enums.craneGoRight
            verticalMove := enums.craneStopV
          }
        }.otherwise {
          when(io.PieceOutDB) {
            when(verticalMove === enums.craneGoDown) {
              grip := enums.craneGrab
              horizontalMove := enums.craneStop
              verticalMove := enums.craneGoUp
              pieceGrabbedFromDB := true.B
            }.otherwise {
              verticalMove := enums.craneGoDown
            }
          }.otherwise {
            when(verticalMove === enums.craneGoDown) {
              verticalMove := enums.craneStopV
            }.otherwise {
              verticalMove := enums.craneGoDown
            }
          }
        }
      }
    }.elsewhen(io.VerticalPos === enums.tcDBHight) {
      when(io.CraneOnTheLeft) {
        when(verticalMove === enums.craneGoUp) {
          horizontalMove := enums.craneGoRight
          verticalMove := enums.craneStopV
        }.otherwise {
          verticalMove := enums.craneGoUp
        }
      }
      when(io.CraneOnTheRight) {
        when(io.PieceOutDB) {
          grip := enums.craneGrab
          horizontalMove := enums.craneStop
          verticalMove := enums.craneGoUp
          pieceGrabbedFromDB := true.B
        }.otherwise {
          horizontalMove := enums.craneStop
          verticalMove := enums.craneStopV
        }
      }
    }.elsewhen(io.VerticalPos === enums.tcFBHight) {
      when(io.CraneOnTheLeft) {
        verticalMove := enums.craneGoUp
      }
    }
  }
  
  // Handshake completion
  when(!io.PieceOutDB && pieceGrabbedFromDB) {
    pieceGrabbedFromDB := false.B
  }
  
  when(!io.FBReady && pieceReleasedOnFB) {
    pieceReleasedOnFB := false.B
  }
}

// Deposit Belt Set
class DepositBeltSet extends Module {
  val io = IO(new Bundle {
    val PieceGrabbedFromDB = Input(Bool())
    val PieceOutArm = Input(Bool())
    val PieceOutDB = Output(Bool())
    val DBReady = Output(Bool())
  })
  
  val belt = Module(new DepositBelt())
  val controller = Module(new DepositBeltCNTR())
  
  // Connect belt to controller
  belt.io.DBMotorSwitch := controller.io.DBMotorSwitch
  belt.io.PieceOutArm := io.PieceOutArm
  belt.io.PieceGrabbedFromDB := io.PieceGrabbedFromDB
  belt.io.DBReady := controller.io.DBReady
  controller.io.DBelt0 := belt.io.DBelt0
  controller.io.DBelt1 := belt.io.DBelt1
  controller.io.DBelt2 := belt.io.DBelt2
  controller.io.DBelt3 := belt.io.DBelt3
  controller.io.PieceGrabbedFromDB := io.PieceGrabbedFromDB
  controller.io.PieceOutArm := io.PieceOutArm
  
  // External connections
  io.PieceOutDB := controller.io.PieceOutDB
  io.DBReady := controller.io.DBReady
}

// Deposit Belt
class DepositBelt extends Module {
  val io = IO(new Bundle {
    val DBMotorSwitch = Input(Bool())
    val PieceOutArm = Input(Bool())
    val PieceGrabbedFromDB = Input(Bool())
    val DBReady = Input(Bool())
    val DBelt0 = Output(Bool())
    val DBelt1 = Output(Bool())
    val DBelt2 = Output(Bool())
    val DBelt3 = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val dbelt0 = RegInit(enums.unitF)
  val dbelt1 = RegInit(enums.unitE) // Non-deterministic initial
  val dbelt2 = RegInit(enums.unitE) // Non-deterministic initial
  val dbelt3 = RegInit(enums.unitE) // Non-deterministic initial
  
  io.DBelt0 := (dbelt0 === enums.unitF)
  io.DBelt1 := (dbelt1 === enums.unitF)
  io.DBelt2 := (dbelt2 === enums.unitF)
  io.DBelt3 := (dbelt3 === enums.unitF)
  
  // Belt motion
  when(io.DBMotorSwitch) {
    dbelt0 := dbelt1
    dbelt1 := dbelt2
    dbelt2 := dbelt3
    dbelt3 := enums.unitE
  }
  
  when(dbelt3 === enums.unitE && io.PieceOutArm && io.DBReady) {
    dbelt3 := enums.unitF
  }
  
  // Piece grabbed by crane
  when(io.PieceGrabbedFromDB) {
    dbelt0 := enums.unitE
  }
}

// Deposit Belt Controller
class DepositBeltCNTR extends Module {
  val io = IO(new Bundle {
    val DBelt0 = Input(Bool())
    val DBelt1 = Input(Bool())
    val DBelt2 = Input(Bool())
    val DBelt3 = Input(Bool())
    val PieceGrabbedFromDB = Input(Bool())
    val PieceOutArm = Input(Bool())
    val DBMotorSwitch = Output(Bool())
    val DBReady = Output(Bool())
    val PieceOutDB = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val dbMotorSwitch = RegInit(false.B)
  val dbReady = RegInit(false.B)
  val pieceOutDB = RegInit(false.B)
  
  io.DBMotorSwitch := dbMotorSwitch
  io.DBReady := dbReady
  io.PieceOutDB := pieceOutDB
  
  // Control handshake with crane
  when((io.DBelt0 && !pieceOutDB && !io.PieceGrabbedFromDB) ||
       (!io.DBelt0 && io.DBelt1 && dbMotorSwitch && !pieceOutDB && !io.PieceGrabbedFromDB)) {
    pieceOutDB := true.B
  }
  
  when(pieceOutDB && io.PieceGrabbedFromDB) {
    pieceOutDB := false.B
  }
  
  // Control handshake with Arm
  when(!io.DBelt3 && !io.PieceOutArm && !dbReady) {
    dbReady := true.B
  }
  
  when(io.PieceOutArm && dbReady) {
    dbReady := false.B
    dbMotorSwitch := true.B
  }
  
  // Control motor
  when(io.DBelt0) {
    dbMotorSwitch := false.B
  }
  
  when(!io.DBelt0 && !dbMotorSwitch && (io.DBelt1 || io.DBelt2 || io.DBelt3)) {
    dbMotorSwitch := true.B
  }.elsewhen(!io.DBelt0 && dbMotorSwitch && io.DBelt1) {
    dbMotorSwitch := false.B
  }
}

// Feed Belt Set
class FeedBeltSet extends Module {
  val io = IO(new Bundle {
    val PieceGrabbedFromFB = Input(Bool())
    val PieceReleasedOnFB = Input(Bool())
    val FBReady = Output(Bool())
    val PieceOutFB = Output(Bool())
  })
  
  val belt = Module(new FeedBelt())
  val controller = Module(new FeedBeltCNTR())
  
  // Connect belt to controller
  belt.io.FBMotorSwitch := controller.io.FBMotorSwitch
  belt.io.PieceReleasedOnFB := io.PieceReleasedOnFB
  belt.io.PieceGrabbedFromFB := io.PieceGrabbedFromFB
  belt.io.FBReady := controller.io.FBReady
  controller.io.FBelt0 := belt.io.FBelt0
  controller.io.FBelt1 := belt.io.FBelt1
  controller.io.FBelt2 := belt.io.FBelt2
  controller.io.FBelt3 := belt.io.FBelt3
  controller.io.PieceGrabbedFromFB := io.PieceGrabbedFromFB
  controller.io.PieceReleasedOnFB := io.PieceReleasedOnFB
  
  // External connections
  io.FBReady := controller.io.FBReady
  io.PieceOutFB := controller.io.PieceOutFB
}

// Feed Belt
class FeedBelt extends Module {
  val io = IO(new Bundle {
    val FBMotorSwitch = Input(Bool())
    val PieceReleasedOnFB = Input(Bool())
    val PieceGrabbedFromFB = Input(Bool())
    val FBReady = Input(Bool())
    val FBelt0 = Output(Bool())
    val FBelt1 = Output(Bool())
    val FBelt2 = Output(Bool())
    val FBelt3 = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val fbelt0 = RegInit(enums.unitE) // Non-deterministic initial
  val fbelt1 = RegInit(enums.unitE) // Non-deterministic initial
  val fbelt2 = RegInit(enums.unitE) // Non-deterministic initial
  val fbelt3 = RegInit(enums.unitE) // Non-deterministic initial
  
  io.FBelt0 := (fbelt0 === enums.unitF)
  io.FBelt1 := (fbelt1 === enums.unitF)
  io.FBelt2 := (fbelt2 === enums.unitF)
  io.FBelt3 := (fbelt3 === enums.unitF)
  
  // Belt motion
  when(io.FBMotorSwitch) {
    fbelt0 := fbelt1
    fbelt1 := fbelt2
    fbelt2 := fbelt3
    fbelt3 := enums.unitE
  }
  
  when(fbelt3 === enums.unitE && io.PieceReleasedOnFB && io.FBReady) {
    fbelt3 := enums.unitF
  }
  
  // Piece grabbed by crane
  when(io.PieceGrabbedFromFB) {
    fbelt0 := enums.unitE
  }
}

// Feed Belt Controller
class FeedBeltCNTR extends Module {
  val io = IO(new Bundle {
    val FBelt0 = Input(Bool())
    val FBelt1 = Input(Bool())
    val FBelt2 = Input(Bool())
    val FBelt3 = Input(Bool())
    val PieceGrabbedFromFB = Input(Bool())
    val PieceReleasedOnFB = Input(Bool())
    val FBMotorSwitch = Output(Bool())
    val FBReady = Output(Bool())
    val PieceOutFB = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val fbMotorSwitch = RegInit(false.B)
  val fbReady = RegInit(false.B)
  val pieceOutFB = RegInit(false.B)
  
  io.FBMotorSwitch := fbMotorSwitch
  io.FBReady := fbReady
  io.PieceOutFB := pieceOutFB
  
  // Control handshake with crane
  when((io.FBelt0 && !pieceOutFB && !io.PieceGrabbedFromFB) ||
       (!io.FBelt0 && io.FBelt1 && fbMotorSwitch && !pieceOutFB && !io.PieceGrabbedFromFB)) {
    pieceOutFB := true.B
  }
  
  when(pieceOutFB && io.PieceGrabbedFromFB) {
    pieceOutFB := false.B
  }
  
  // Control handshake with Arm
  when(!io.FBelt3 && !io.PieceReleasedOnFB && !fbReady) {
    fbReady := true.B
  }
  
  when(io.PieceReleasedOnFB && fbReady) {
    fbReady := false.B
    fbMotorSwitch := true.B
  }
  
  // Control motor
  when(io.FBelt0) {
    fbMotorSwitch := false.B
  }
  
  when(!io.FBelt0 && !fbMotorSwitch && (io.FBelt1 || io.FBelt2 || io.FBelt3)) {
    fbMotorSwitch := true.B
  }.elsewhen(!io.FBelt0 && fbMotorSwitch && io.FBelt1) {
    fbMotorSwitch := false.B
  }
}

// Rotary Table Set
class RotaryTableSet extends Module {
  val io = IO(new Bundle {
    val PieceOutFB = Input(Bool())
    val PieceGrabbedFromRT = Input(Bool())
    val PieceGrabbedFromFB = Output(Bool())
    val RTOutReady = Output(Bool())
  })
  
  val table = Module(new RotaryTable())
  val controller = Module(new RotaryTableCNTR())
  
  // Connect table to controller
  table.io.RTRotaryMotor := controller.io.RTRotaryMotor
  table.io.RTVerticalMotor := controller.io.RTVerticalMotor
  controller.io.RTOnFB := table.io.RTOnFB
  controller.io.RTOnArm := table.io.RTOnArm
  controller.io.RTOnTop := table.io.RTOnTop
  controller.io.RTOnBottom := table.io.RTOnBottom
  controller.io.PieceOutFB := io.PieceOutFB
  controller.io.PieceGrabbedFromRT := io.PieceGrabbedFromRT
  
  // External connections
  io.PieceGrabbedFromFB := controller.io.PieceGrabbedFromFB
  io.RTOutReady := controller.io.RTOutReady
}

// Rotary Table
class RotaryTable extends Module {
  val io = IO(new Bundle {
    val RTRotaryMotor = Input(UInt(2.W))
    val RTVerticalMotor = Input(UInt(2.W))
    val RTOnFB = Output(Bool())
    val RTOnArm = Output(Bool())
    val RTOnTop = Output(Bool())
    val RTOnBottom = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val rtAngle = RegInit(enums.rtS) // Non-deterministic initial
  val rtHeight = RegInit(enums.rtTop) // Non-deterministic initial
  
  // Position sensors
  io.RTOnFB := (rtAngle === enums.rtS)
  io.RTOnArm := (rtAngle === enums.rtSE)
  io.RTOnTop := (rtHeight === enums.rtTop)
  io.RTOnBottom := (rtHeight === enums.rtBot)
  
  // Rotary movement
  when(io.RTRotaryMotor === enums.rtCWise) {
    when(rtAngle === enums.rtSE) {
      rtAngle := enums.rtSSE
    }.elsewhen(rtAngle === enums.rtSSE) {
      rtAngle := enums.rtS
    }
  }
  
  when(io.RTRotaryMotor === enums.rtCCWise) {
    when(rtAngle === enums.rtS) {
      rtAngle := enums.rtSSE
    }.elsewhen(rtAngle === enums.rtSSE) {
      rtAngle := enums.rtSE
    }
  }
  
  // Vertical movement
  when(io.RTVerticalMotor === enums.rtGoUp) {
    when(rtHeight === enums.rtMid) {
      rtHeight := enums.rtTop
    }.elsewhen(rtHeight === enums.rtBot) {
      rtHeight := enums.rtMid
    }
  }
  
  when(io.RTVerticalMotor === enums.rtGoDown) {
    when(rtHeight === enums.rtMid) {
      rtHeight := enums.rtBot
    }.elsewhen(rtHeight === enums.rtTop) {
      rtHeight := enums.rtMid
    }
  }
}

// Rotary Table Controller
class RotaryTableCNTR extends Module {
  val io = IO(new Bundle {
    val PieceOutFB = Input(Bool())
    val PieceGrabbedFromRT = Input(Bool())
    val RTOnFB = Input(Bool())
    val RTOnArm = Input(Bool())
    val RTOnTop = Input(Bool())
    val RTOnBottom = Input(Bool())
    val RTRotaryMotor = Output(UInt(2.W))
    val RTVerticalMotor = Output(UInt(2.W))
    val PieceGrabbedFromFB = Output(Bool())
    val RTOutReady = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val rtRotaryMotor = RegInit(enums.rtStop)
  val rtVerticalMotor = RegInit(enums.rtStopV)
  val pieceGrabbedFromFB = RegInit(false.B)
  val rtOutReady = RegInit(false.B)
  val tableLoaded = RegInit(false.B)
  
  io.RTRotaryMotor := rtRotaryMotor
  io.RTVerticalMotor := rtVerticalMotor
  io.PieceGrabbedFromFB := pieceGrabbedFromFB
  io.RTOutReady := rtOutReady
  
  // Complex state machine for rotary table control
  when(tableLoaded) {
    when(io.RTOnTop) {
      when(io.RTOnFB) {
        rtRotaryMotor := enums.rtCCWise
        rtVerticalMotor := enums.rtStopV
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        when(rtRotaryMotor === enums.rtCCWise) {
          rtRotaryMotor := enums.rtStop
          rtVerticalMotor := enums.rtStopV
          rtOutReady := true.B
        }.otherwise {
          when(rtRotaryMotor === enums.rtStop) {
            rtRotaryMotor := enums.rtCCWise
          }
        }
      }.elsewhen(io.RTOnArm) {
        when(io.PieceGrabbedFromRT) {
          rtOutReady := false.B
          rtRotaryMotor := enums.rtCWise
          rtVerticalMotor := enums.rtGoDown
          tableLoaded := false.B
        }.otherwise {
          rtOutReady := true.B
          rtRotaryMotor := enums.rtStop
          rtVerticalMotor := enums.rtStopV
        }
      }
    }.elsewhen(!io.RTOnTop && !io.RTOnBottom) {
      when(io.RTOnFB) {
        rtRotaryMotor := enums.rtCCWise
        when(rtVerticalMotor === enums.rtGoUp) {
          rtVerticalMotor := enums.rtStopV
        }.otherwise {
          rtVerticalMotor := enums.rtGoUp
        }
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        when(rtRotaryMotor === enums.rtCCWise && rtVerticalMotor === enums.rtGoUp) {
          rtRotaryMotor := enums.rtStop
          rtVerticalMotor := enums.rtStopV
          rtOutReady := true.B
        }.otherwise {
          when(rtRotaryMotor =/= enums.rtCCWise && rtVerticalMotor === enums.rtGoUp) {
            rtRotaryMotor := enums.rtCCWise
            rtVerticalMotor := enums.rtStopV
          }.elsewhen(rtRotaryMotor === enums.rtCCWise && rtVerticalMotor =/= enums.rtGoUp) {
            rtRotaryMotor := enums.rtStop
            rtVerticalMotor := enums.rtGoUp
          }.otherwise {
            rtRotaryMotor := enums.rtCCWise
            rtVerticalMotor := enums.rtGoUp
          }
        }
      }.elsewhen(io.RTOnArm) {
        rtRotaryMotor := enums.rtStop
        when(rtVerticalMotor === enums.rtGoUp) {
          rtVerticalMotor := enums.rtStopV
          rtOutReady := true.B
        }.otherwise {
          rtVerticalMotor := enums.rtGoUp
        }
      }
    }.elsewhen(io.RTOnBottom) {
      when(io.RTOnFB) {
        rtRotaryMotor := enums.rtCCWise
        rtVerticalMotor := enums.rtGoUp
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        rtVerticalMotor := enums.rtGoUp
        when(rtRotaryMotor === enums.rtCCWise) {
          rtRotaryMotor := enums.rtStop
        }.otherwise {
          rtRotaryMotor := enums.rtCCWise
        }
      }.elsewhen(io.RTOnArm) {
        rtRotaryMotor := enums.rtStop
        rtVerticalMotor := enums.rtGoUp
      }
    }
  }.otherwise { // tableLoaded === false
    when(io.RTOnTop) {
      when(io.RTOnFB) {
        rtRotaryMotor := enums.rtStop
        rtVerticalMotor := enums.rtGoDown
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        rtVerticalMotor := enums.rtGoDown
        when(rtRotaryMotor === enums.rtCWise) {
          rtRotaryMotor := enums.rtStop
        }.otherwise {
          rtRotaryMotor := enums.rtCWise
        }
      }.elsewhen(io.RTOnArm) {
        rtRotaryMotor := enums.rtCWise
        rtVerticalMotor := enums.rtGoDown
      }
    }.elsewhen(!io.RTOnTop && !io.RTOnBottom) {
      when(io.RTOnFB) {
        when(rtVerticalMotor === enums.rtGoDown) {
          when(io.PieceOutFB) {
            tableLoaded := true.B
            pieceGrabbedFromFB := true.B
            rtRotaryMotor := enums.rtCCWise
            rtVerticalMotor := enums.rtGoUp
          }.otherwise {
            rtRotaryMotor := enums.rtStop
            rtVerticalMotor := enums.rtStopV
          }
        }.otherwise {
          rtRotaryMotor := enums.rtStop
          rtVerticalMotor := enums.rtGoDown
        }
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        when(rtRotaryMotor === enums.rtCWise && rtVerticalMotor === enums.rtGoDown) {
          when(io.PieceOutFB) {
            tableLoaded := true.B
            pieceGrabbedFromFB := true.B
            rtRotaryMotor := enums.rtCCWise
            rtVerticalMotor := enums.rtGoUp
          }.otherwise {
            rtRotaryMotor := enums.rtStop
            rtVerticalMotor := enums.rtStopV
          }
        }.otherwise {
          when(rtRotaryMotor =/= enums.rtCWise && rtVerticalMotor === enums.rtGoDown) {
            rtRotaryMotor := enums.rtCWise
            rtVerticalMotor := enums.rtStopV
          }.elsewhen(rtRotaryMotor === enums.rtCWise && rtVerticalMotor =/= enums.rtGoDown) {
            rtRotaryMotor := enums.rtStop
            rtVerticalMotor := enums.rtGoDown
          }.otherwise {
            rtRotaryMotor := enums.rtCWise
            rtVerticalMotor := enums.rtGoDown
          }
        }
      }.elsewhen(io.RTOnArm) {
        rtRotaryMotor := enums.rtCWise
        when(rtVerticalMotor === enums.rtGoDown) {
          rtVerticalMotor := enums.rtStopV
        }.otherwise {
          rtVerticalMotor := enums.rtGoDown
        }
      }
    }.elsewhen(io.RTOnBottom) {
      when(io.RTOnFB) {
        when(io.PieceOutFB) {
          pieceGrabbedFromFB := true.B
          rtRotaryMotor := enums.rtCCWise
          rtVerticalMotor := enums.rtGoUp
          tableLoaded := true.B
        }.otherwise {
          rtRotaryMotor := enums.rtStop
          rtVerticalMotor := enums.rtStopV
        }
      }.elsewhen(!io.RTOnFB && !io.RTOnArm) {
        rtVerticalMotor := enums.rtStopV
        when(rtRotaryMotor === enums.rtCWise) {
          rtRotaryMotor := enums.rtStop
        }.otherwise {
          rtRotaryMotor := enums.rtCWise
        }
      }.elsewhen(io.RTOnArm) {
        rtRotaryMotor := enums.rtCWise
        rtVerticalMotor := enums.rtStopV
      }
    }
  }
  
  // Handshake completion
  when(!io.PieceOutFB && pieceGrabbedFromFB) {
    pieceGrabbedFromFB := false.B
  }
  
  when(rtOutReady && io.PieceGrabbedFromRT) {
    rtOutReady := false.B
  }
}

// Press Set
class PressSet extends Module {
  val io = IO(new Bundle {
    val ArmLoadedPress = Input(Bool())
    val ArmUnLoadedPress = Input(Bool())
    val PressReadyToBeLoaded = Output(Bool())
    val PressReadyToBeUnLoaded = Output(Bool())
  })
  
  val press = Module(new Press())
  val controller = Module(new PressCNTR())
  
  // Connect press to controller
  press.io.PressMotor := controller.io.PressMotor
  controller.io.PressPosition := press.io.PressPosition
  controller.io.ArmLoadedPress := io.ArmLoadedPress
  controller.io.ArmUnLoadedPress := io.ArmUnLoadedPress
  
  // External connections
  io.PressReadyToBeLoaded := controller.io.PressReadyToBeLoaded
  io.PressReadyToBeUnLoaded := controller.io.PressReadyToBeUnLoaded
}

// Press
class Press extends Module {
  val io = IO(new Bundle {
    val PressMotor = Input(UInt(2.W))
    val PressPosition = Output(UInt(2.W))
  })
  
  val enums = new ProductionCellEnums()
  
  val pressPosition = RegInit(enums.pressMid)
  io.PressPosition := pressPosition
  
  when(io.PressMotor === enums.pressGoUp) {
    when(pressPosition === enums.pressMid) {
      pressPosition := enums.pressTop
    }.elsewhen(pressPosition === enums.pressBot) {
      pressPosition := enums.pressMid
    }
  }
  
  when(io.PressMotor === enums.pressGoDown) {
    when(pressPosition === enums.pressTop) {
      pressPosition := enums.pressMid
    }.elsewhen(pressPosition === enums.pressMid) {
      pressPosition := enums.pressBot
    }
  }
}

// Press Controller
class PressCNTR extends Module {
  val io = IO(new Bundle {
    val PressPosition = Input(UInt(2.W))
    val ArmLoadedPress = Input(Bool())
    val ArmUnLoadedPress = Input(Bool())
    val PressMotor = Output(UInt(2.W))
    val PressReadyToBeLoaded = Output(Bool())
    val PressReadyToBeUnLoaded = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val pressMotor = RegInit(enums.pressStop)
  val pressReadyToBeLoaded = RegInit(false.B)
  val pressReadyToBeUnLoaded = RegInit(false.B)
  val pressLoaded = RegInit(false.B)
  
  io.PressMotor := pressMotor
  io.PressReadyToBeLoaded := pressReadyToBeLoaded
  io.PressReadyToBeUnLoaded := pressReadyToBeUnLoaded
  
  when(pressLoaded) {
    when(io.PressPosition === enums.pressTop) {
      pressMotor := enums.pressGoDown
    }.elsewhen(io.PressPosition === enums.pressMid) {
      when(pressMotor === enums.pressGoDown) {
        pressMotor := enums.pressStop
        pressReadyToBeUnLoaded := true.B
      }.otherwise {
        pressMotor := enums.pressGoDown
      }
    }.elsewhen(io.PressPosition === enums.pressBot) {
      when(io.ArmUnLoadedPress && pressReadyToBeUnLoaded) {
        pressMotor := enums.pressGoUp
        pressLoaded := false.B
        pressReadyToBeUnLoaded := false.B
      }.otherwise {
        pressMotor := enums.pressStop
      }
    }
  }.otherwise { // pressLoaded === false
    when(io.PressPosition === enums.pressTop) {
      when(pressMotor === enums.pressGoDown) {
        pressMotor := enums.pressStop
        pressReadyToBeLoaded := true.B
      }.otherwise {
        pressMotor := enums.pressGoDown
      }
    }.elsewhen(io.PressPosition === enums.pressMid) {
      when(io.ArmLoadedPress) {
        pressLoaded := true.B
        pressMotor := enums.pressGoUp
      }.otherwise {
        pressMotor := enums.pressStop
        pressReadyToBeLoaded := true.B
      }
    }
  }
  
  // Handshake completion
  when(io.ArmLoadedPress && pressReadyToBeLoaded) {
    pressReadyToBeLoaded := false.B
  }
  
  when(pressReadyToBeUnLoaded && io.ArmUnLoadedPress) {
    pressReadyToBeUnLoaded := false.B
  }
}

// Arm Set
class ArmSet extends Module {
  val io = IO(new Bundle {
    val DBReady = Input(Bool())
    val PressReadyToBeUnLoaded = Input(Bool())
    val PressReadyToBeLoaded = Input(Bool())
    val RTOutReady = Input(Bool())
    val PieceOutArm = Output(Bool())
    val ArmUnLoadedPress = Output(Bool())
    val ArmLoadedPress = Output(Bool())
    val PieceGrabbedFromRT = Output(Bool())
  })
  
  val arm = Module(new RobotArm())
  val controller = Module(new RobotArmCNTR())
  
  // Connect arm to controller
  arm.io.RAExtendLoadArm := controller.io.RAExtendLoadArm
  arm.io.RAExtendUnLoadArm := controller.io.RAExtendUnLoadArm
  arm.io.RARotaryMotor := controller.io.RARotaryMotor
  controller.io.RALoadArmExtended := arm.io.RALoadArmExtended
  controller.io.RALoadArmRetracted := arm.io.RALoadArmRetracted
  controller.io.RAUnLoadArmExtended := arm.io.RAUnLoadArmExtended
  controller.io.RAUnLoadArmRetracted := arm.io.RAUnLoadArmRetracted
  controller.io.RAArmOverRT := arm.io.RAArmOverRT
  controller.io.RAArmOverUnLoadedPress := arm.io.RAArmOverUnLoadedPress
  controller.io.RAArmOverLoadedPress := arm.io.RAArmOverLoadedPress
  controller.io.RAArmOverDB := arm.io.RAArmOverDB
  controller.io.DBReady := io.DBReady
  controller.io.PressReadyToBeUnLoaded := io.PressReadyToBeUnLoaded
  controller.io.PressReadyToBeLoaded := io.PressReadyToBeLoaded
  controller.io.RTOutReady := io.RTOutReady
  
  // External connections
  io.PieceOutArm := controller.io.PieceOutArm
  io.ArmUnLoadedPress := controller.io.ArmUnLoadedPress
  io.ArmLoadedPress := controller.io.ArmLoadedPress
  io.PieceGrabbedFromRT := controller.io.PieceGrabbedFromRT
}

// Robot Arm
class RobotArm extends Module {
  val io = IO(new Bundle {
    val RAExtendLoadArm = Input(UInt(2.W))
    val RAExtendUnLoadArm = Input(UInt(2.W))
    val RARotaryMotor = Input(UInt(2.W))
    val RALoadArmExtended = Output(Bool())
    val RALoadArmRetracted = Output(Bool())
    val RAUnLoadArmExtended = Output(Bool())
    val RAUnLoadArmRetracted = Output(Bool())
    val RAArmOverRT = Output(Bool())
    val RAArmOverUnLoadedPress = Output(Bool())
    val RAArmOverLoadedPress = Output(Bool())
    val RAArmOverDB = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val raLoadArm = RegInit(enums.armRetracted)
  val raUnLoadArm = RegInit(enums.armRetracted)
  val raAnglePos = RegInit(enums.armOverRT) // Non-deterministic initial
  
  // Position sensors
  io.RALoadArmExtended := (raLoadArm === enums.armExtended)
  io.RALoadArmRetracted := (raLoadArm === enums.armRetracted)
  io.RAUnLoadArmExtended := (raUnLoadArm === enums.armExtended)
  io.RAUnLoadArmRetracted := (raUnLoadArm === enums.armRetracted)
  io.RAArmOverRT := (raAnglePos === enums.armOverRT)
  io.RAArmOverUnLoadedPress := (raAnglePos === enums.armOverUnLoadedPress)
  io.RAArmOverLoadedPress := (raAnglePos === enums.armOverLoadedPress)
  io.RAArmOverDB := (raAnglePos === enums.armOverDB)
  
  // Load arm horizontal movement
  when(io.RAExtendLoadArm === enums.armExtend) {
    when(raLoadArm === enums.armRetracted) {
      raLoadArm := enums.armMiddle
    }.elsewhen(raLoadArm === enums.armMiddle) {
      raLoadArm := enums.armExtended
    }
  }
  
  when(io.RAExtendLoadArm === enums.armRetract) {
    when(raLoadArm === enums.armExtended) {
      raLoadArm := enums.armMiddle
    }.elsewhen(raLoadArm === enums.armMiddle) {
      raLoadArm := enums.armRetracted
    }
  }
  
  // Unload arm horizontal movement
  when(io.RAExtendUnLoadArm === enums.armExtend) {
    when(raUnLoadArm === enums.armRetracted) {
      raUnLoadArm := enums.armMiddle
    }.elsewhen(raUnLoadArm === enums.armMiddle) {
      raUnLoadArm := enums.armExtended
    }
  }
  
  when(io.RAExtendUnLoadArm === enums.armRetract) {
    when(raUnLoadArm === enums.armExtended) {
      raUnLoadArm := enums.armMiddle
    }.elsewhen(raUnLoadArm === enums.armMiddle) {
      raUnLoadArm := enums.armRetracted
    }
  }
  
  // Arm rotation
  when(io.RARotaryMotor === enums.armCCWise) {
    when(raAnglePos === enums.armOverRT) {
      raAnglePos := enums.armOverUnLoadedPress
    }.elsewhen(raAnglePos === enums.armOverUnLoadedPress) {
      raAnglePos := enums.armOverDB
    }.elsewhen(raAnglePos === enums.armOverDB) {
      raAnglePos := enums.armOverLoadedPress
    }
  }
  
  when(io.RARotaryMotor === enums.armCWise) {
    when(raAnglePos === enums.armOverLoadedPress) {
      raAnglePos := enums.armOverDB
    }.elsewhen(raAnglePos === enums.armOverDB) {
      raAnglePos := enums.armOverUnLoadedPress
    }.elsewhen(raAnglePos === enums.armOverUnLoadedPress) {
      raAnglePos := enums.armOverRT
    }
  }
}

// Robot Arm Controller
class RobotArmCNTR extends Module {
  val io = IO(new Bundle {
    val RALoadArmExtended = Input(Bool())
    val RALoadArmRetracted = Input(Bool())
    val RAUnLoadArmExtended = Input(Bool())
    val RAUnLoadArmRetracted = Input(Bool())
    val RAArmOverRT = Input(Bool())
    val RAArmOverUnLoadedPress = Input(Bool())
    val RAArmOverLoadedPress = Input(Bool())
    val RAArmOverDB = Input(Bool())
    val DBReady = Input(Bool())
    val PressReadyToBeUnLoaded = Input(Bool())
    val PressReadyToBeLoaded = Input(Bool())
    val RTOutReady = Input(Bool())
    val RAExtendLoadArm = Output(UInt(2.W))
    val RAExtendUnLoadArm = Output(UInt(2.W))
    val RARotaryMotor = Output(UInt(2.W))
    val PieceOutArm = Output(Bool())
    val ArmUnLoadedPress = Output(Bool())
    val ArmLoadedPress = Output(Bool())
    val PieceGrabbedFromRT = Output(Bool())
  })
  
  val enums = new ProductionCellEnums()
  
  val raExtendLoadArm = RegInit(enums.armStopH)
  val raExtendUnLoadArm = RegInit(enums.armStopH)
  val raRotaryMotor = RegInit(enums.armStopA)
  val pieceOutArm = RegInit(false.B)
  val armUnLoadedPress = RegInit(false.B)
  val armLoadedPress = RegInit(false.B)
  val pieceGrabbedFromRT = RegInit(false.B)
  val loadArmLoaded = RegInit(false.B)
  val unLoadArmLoaded = RegInit(false.B)
  
  io.RAExtendLoadArm := raExtendLoadArm
  io.RAExtendUnLoadArm := raExtendUnLoadArm
  io.RARotaryMotor := raRotaryMotor
  io.PieceOutArm := pieceOutArm
  io.ArmUnLoadedPress := armUnLoadedPress
  io.ArmLoadedPress := armLoadedPress
  io.PieceGrabbedFromRT := pieceGrabbedFromRT
  
  // Complex robot arm control logic
  when(!loadArmLoaded && !unLoadArmLoaded) {
    when(io.RAArmOverRT) {
      when(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
           raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
           raRotaryMotor === enums.armStopA) {
        when(io.RTOutReady) {
          raExtendLoadArm := enums.armExtend
        }.otherwise {
          when(io.PressReadyToBeUnLoaded) {
            raRotaryMotor := enums.armCCWise
          }
        }
      }.elsewhen(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
                 raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
                 raRotaryMotor === enums.armCCWise) {
        when(io.PressReadyToBeUnLoaded) {
          raRotaryMotor := enums.armStopA
          raExtendUnLoadArm := enums.armExtend
        }
      }.elsewhen(!io.RALoadArmRetracted && !io.RALoadArmExtended &&
                 io.RAUnLoadArmRetracted &&
                 raExtendLoadArm === enums.armExtend && raExtendUnLoadArm === enums.armStopH &&
                 raRotaryMotor === enums.armStopA) {
        raExtendLoadArm := enums.armRetract
        loadArmLoaded := true.B
        pieceGrabbedFromRT := true.B
      }
    }
    
    when(io.RAArmOverLoadedPress) {
      when(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
           raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
           raRotaryMotor === enums.armStopA) {
        raRotaryMotor := enums.armCWise
      }
      when(!io.RALoadArmRetracted && !io.RALoadArmExtended &&
           io.RAUnLoadArmRetracted &&
           raExtendLoadArm === enums.armRetract && raExtendUnLoadArm === enums.armStopH &&
           raRotaryMotor === enums.armStopA) {
        raExtendLoadArm := enums.armStopH
        raRotaryMotor := enums.armCWise
      }
    }
    
    when(io.RAArmOverUnLoadedPress) {
      when(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
           raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
           raRotaryMotor === enums.armStopA) {
        when(io.PressReadyToBeUnLoaded) {
          raExtendLoadArm := enums.armExtend
        }.otherwise {
          raRotaryMotor := enums.armCWise
        }
      }.elsewhen(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
                 raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
                 raRotaryMotor === enums.armCWise) {
        raRotaryMotor := enums.armStopA
        when(io.RTOutReady) {
          raExtendLoadArm := enums.armExtend
        }
      }.elsewhen(io.RALoadArmRetracted && !io.RAUnLoadArmRetracted &&
                 !io.RAUnLoadArmExtended &&
                 raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armExtend &&
                 raRotaryMotor === enums.armStopA) {
        raExtendUnLoadArm := enums.armRetract
        armUnLoadedPress := true.B
        unLoadArmLoaded := true.B
      }
    }
    
    when(io.RAArmOverDB) {
      when(io.RALoadArmRetracted && io.RAUnLoadArmRetracted &&
           raExtendLoadArm === enums.armStopH && raExtendUnLoadArm === enums.armStopH &&
           raRotaryMotor === enums.armStopA) {
        raRotaryMotor := enums.armCWise
      }
      when(raExtendUnLoadArm === enums.armRetract && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        raExtendUnLoadArm := enums.armStopH
        raRotaryMotor := enums.armCWise
      }
      when(raRotaryMotor === enums.armCWise) {
        when(io.PressReadyToBeUnLoaded && !io.RTOutReady) {
          raRotaryMotor := enums.armStopA
          raExtendUnLoadArm := enums.armExtend
        }
      }
    }
  }.elsewhen(!loadArmLoaded && unLoadArmLoaded) {
    when(io.RAArmOverUnLoadedPress) {
      when(raRotaryMotor === enums.armCCWise) {
        raRotaryMotor := enums.armStopA
        raExtendUnLoadArm := enums.armExtend
      }
      when(raExtendUnLoadArm === enums.armRetract && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        raExtendUnLoadArm := enums.armStopH
        raRotaryMotor := enums.armCCWise
      }
    }
    
    when(io.RAArmOverDB) {
      when(raExtendUnLoadArm === enums.armExtend && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        when(io.DBReady) {
          raExtendUnLoadArm := enums.armRetract
          pieceOutArm := true.B
          unLoadArmLoaded := false.B
        }.otherwise {
          raExtendUnLoadArm := enums.armStopH
        }
      }
      when(raExtendUnLoadArm === enums.armStopH && io.RAUnLoadArmExtended && io.DBReady) {
        raExtendUnLoadArm := enums.armRetract
        pieceOutArm := true.B
        unLoadArmLoaded := false.B
      }
    }
  }.elsewhen(loadArmLoaded && !unLoadArmLoaded) {
    when(io.RAArmOverRT) {
      when(raRotaryMotor === enums.armCCWise) {
        when(io.PressReadyToBeUnLoaded) {
          raRotaryMotor := enums.armStopA
          raExtendUnLoadArm := enums.armExtend
        }
      }
      when(raExtendLoadArm === enums.armRetract && !io.RALoadArmExtended &&
           !io.RALoadArmRetracted) {
        raExtendLoadArm := enums.armStopH
        raRotaryMotor := enums.armCCWise
      }
    }
    
    when(io.RAArmOverLoadedPress) {
      when(raExtendLoadArm === enums.armStopH && raRotaryMotor === enums.armStopA &&
           raExtendUnLoadArm === enums.armStopH &&
           io.RALoadArmRetracted) {
        when(io.PressReadyToBeLoaded) {
          raExtendLoadArm := enums.armExtend
        }
      }
      when(raExtendLoadArm === enums.armExtend && !io.RALoadArmExtended &&
           !io.RALoadArmRetracted) {
        raExtendLoadArm := enums.armRetract
        armLoadedPress := true.B
        loadArmLoaded := false.B
      }
    }
    
    when(io.RAArmOverUnLoadedPress) {
      when(raExtendUnLoadArm === enums.armExtend && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        when(io.PressReadyToBeUnLoaded) {
          armUnLoadedPress := true.B
          unLoadArmLoaded := true.B
          raExtendUnLoadArm := enums.armRetract
        }
      }
    }
    
    when(io.RAArmOverDB) {
      when(raRotaryMotor === enums.armCCWise) {
        raRotaryMotor := enums.armStopA
        when(io.PressReadyToBeLoaded) {
          raExtendLoadArm := enums.armExtend
        }
      }
      when(raExtendUnLoadArm === enums.armRetract && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        raExtendUnLoadArm := enums.armStopH
        raRotaryMotor := enums.armCCWise
      }
    }
  }.otherwise { // loadArmLoaded && unLoadArmLoaded
    when(io.RAArmOverUnLoadedPress) {
      when(raRotaryMotor === enums.armCCWise) {
        raRotaryMotor := enums.armStopA
        raExtendUnLoadArm := enums.armExtend
      }
      when(raExtendUnLoadArm === enums.armRetract &&
           !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        raExtendUnLoadArm := enums.armStopH
        raRotaryMotor := enums.armCCWise
      }
    }
    
    when(io.RAArmOverDB) {
      when(raExtendUnLoadArm === enums.armExtend && !io.RAUnLoadArmExtended &&
           !io.RAUnLoadArmRetracted) {
        raExtendUnLoadArm := enums.armStopH
        when(io.DBReady) {
          raExtendUnLoadArm := enums.armRetract
          unLoadArmLoaded := false.B
          pieceOutArm := true.B
        }
      }
      when(raExtendUnLoadArm === enums.armStopH && io.RAUnLoadArmExtended) {
        when(io.DBReady) {
          raExtendUnLoadArm := enums.armRetract
          unLoadArmLoaded := false.B
          pieceOutArm := true.B
        }
      }
    }
  }
  
  // Handshake completion
  when(!io.DBReady && pieceOutArm) {
    pieceOutArm := false.B
  }
  
  when(!io.PressReadyToBeUnLoaded && armUnLoadedPress) {
    armUnLoadedPress := false.B
  }
  
  when(armLoadedPress && !io.PressReadyToBeLoaded) {
    armLoadedPress := false.B
  }
  
  when(!io.RTOutReady && pieceGrabbedFromRT) {
    pieceGrabbedFromRT := false.B
  }
}

// Main object for Verilog generation
object VerilogGenerator extends App {
  emitVerilog(new ProductionCell(), args)
}