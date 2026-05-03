package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object PlayerStatus {
  val HIT = 0.U(2.W)
  val WAIT_GOING = 1.U(2.W)
  val WAIT_COMING = 2.U(2.W)
}

object ActionType {
  val HIT = 1.U(1.W)
  val IDLE = 0.U(1.W)
}

object BallStatus {
  val TO_A = 0.U(2.W)
  val TO_B = 1.U(2.W)
  val OUT_OF_PLAY = 2.U(2.W)
}

// Player module
class Player extends Module {
  val io = IO(new Bundle {
    val opponent = Input(UInt(1.W))
    val out = Output(UInt(1.W))
    val state = Output(UInt(2.W))
  })
  
  val state = RegInit(PlayerStatus.HIT)
  
  // Non-deterministic choice between WAIT_COMING and HIT
  // Using a simple counter-based approach to simulate non-determinism
  val ndCounter = RegInit(0.U(8.W))
  ndCounter := ndCounter + 1.U
  val r_state = Mux(ndCounter(0), PlayerStatus.WAIT_COMING, PlayerStatus.HIT)
  
  io.out := Mux(state === PlayerStatus.HIT, ActionType.HIT, ActionType.IDLE)
  io.state := state
  
  switch(state) {
    is(PlayerStatus.HIT) {
      when(io.opponent === ActionType.IDLE) {
        state := PlayerStatus.WAIT_GOING
      }.elsewhen(io.opponent === ActionType.HIT) {
        state := PlayerStatus.WAIT_COMING
      }
    }
    is(PlayerStatus.WAIT_GOING) {
      when(io.opponent === ActionType.HIT) {
        state := PlayerStatus.WAIT_COMING
      }
    }
    is(PlayerStatus.WAIT_COMING) {
      state := r_state
    }
  }
}

// Ball module
class Ball extends Module {
  val io = IO(new Bundle {
    val action_A = Input(UInt(1.W))
    val action_B = Input(UInt(1.W))
    val init = Input(UInt(2.W))
    val state = Output(UInt(2.W))
  })
  
  val state = RegInit(io.init)
  io.state := state
  
  switch(state) {
    is(BallStatus.TO_A) {
      when(io.action_A === ActionType.HIT) {
        state := BallStatus.TO_B
      }.elsewhen((io.action_A === ActionType.IDLE) && (io.action_B === ActionType.HIT)) {
        state := BallStatus.OUT_OF_PLAY
      }
    }
    is(BallStatus.TO_B) {
      when(io.action_B === ActionType.HIT) {
        state := BallStatus.TO_A
      }.elsewhen((io.action_B === ActionType.IDLE) && (io.action_A === ActionType.HIT)) {
        state := BallStatus.OUT_OF_PLAY
      }
    }
    is(BallStatus.OUT_OF_PLAY) {
      state := BallStatus.OUT_OF_PLAY
    }
  }
}

// Top-level ping_pong module
class PingPong extends Module {
  val io = IO(new Bundle {
    // Outputs to preserve the design signals
    val action_A = Output(UInt(1.W))
    val action_B = Output(UInt(1.W))
    val state_A = Output(UInt(2.W))
    val state_B = Output(UInt(2.W))
    val state_ball_1 = Output(UInt(2.W))
    val state_ball_2 = Output(UInt(2.W))
  })
  
  // Instantiate players
  val player_A = Module(new Player())
  val player_B = Module(new Player())
  
  // Connect players
  player_A.io.opponent := player_B.io.out
  player_B.io.opponent := player_A.io.out
  
  // Instantiate balls
  val ball_1 = Module(new Ball())
  val ball_2 = Module(new Ball())
  
  // Connect balls
  ball_1.io.action_A := player_A.io.out
  ball_1.io.action_B := player_B.io.out
  ball_1.io.init := BallStatus.TO_A
  
  ball_2.io.action_A := player_A.io.out
  ball_2.io.action_B := player_B.io.out
  ball_2.io.init := BallStatus.TO_B
  
  // Connect outputs
  io.action_A := player_A.io.out
  io.action_B := player_B.io.out
  io.state_A := player_A.io.state
  io.state_B := player_B.io.state
  io.state_ball_1 := ball_1.io.state
  io.state_ball_2 := ball_2.io.state
}

// Main object for Verilog generation
object VerilogGenerator extends App {
  emitVerilog(new PingPong(), args)
}