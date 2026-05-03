package llmverify

import chisel3._
import chisel3.util._

// State enumeration
class State extends Bundle {
  val value = UInt(4.W)
}

object State {
  val BLNK = 0.U(4.W)
  val one = 1.U(4.W)
  val two = 2.U(4.W)
  val three = 3.U(4.W)
  val four = 4.U(4.W)
  val five = 5.U(4.W)
  val six = 6.U(4.W)
  val seven = 7.U(4.W)
  val eight = 8.U(4.W)
}

// Direction enumeration
object Dirn {
  val left = 0.U(2.W)
  val right = 1.U(2.W)
  val top = 2.U(2.W)
  val down = 3.U(2.W)
}

// Square module - holds the state of one square
class Square extends Module {
  val io = IO(new Bundle {
    val init = Input(UInt(4.W))
    val in = Input(UInt(4.W))
    val out = Output(UInt(4.W))
  })
  
  val state = RegInit(io.init)
  io.out := state
  
  state := io.in
}

// Next state function module
class Nsfunction extends Module {
  val io = IO(new Bundle {
    val state11 = Input(UInt(4.W))
    val state12 = Input(UInt(4.W))
    val state13 = Input(UInt(4.W))
    val state21 = Input(UInt(4.W))
    val state22 = Input(UInt(4.W))
    val state23 = Input(UInt(4.W))
    val state31 = Input(UInt(4.W))
    val state32 = Input(UInt(4.W))
    val state33 = Input(UInt(4.W))
    
    val ns_state11 = Output(UInt(4.W))
    val ns_state12 = Output(UInt(4.W))
    val ns_state13 = Output(UInt(4.W))
    val ns_state21 = Output(UInt(4.W))
    val ns_state22 = Output(UInt(4.W))
    val ns_state23 = Output(UInt(4.W))
    val ns_state31 = Output(UInt(4.W))
    val ns_state32 = Output(UInt(4.W))
    val ns_state33 = Output(UInt(4.W))
  })
  
  // Nondeterministic direction - using LFSR for pseudo-random choice
  val lfsr = RegInit(1.U(8.W))
  lfsr := Cat(lfsr(6,0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  val nd = lfsr(1,0) // 2-bit pseudo-random direction
  
  // Corner squares logic
  io.ns_state11 := Mux(
    (io.state12 === State.BLNK && nd === Dirn.left) || (io.state21 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state11 === State.BLNK && nd === Dirn.right,
      io.state12,
      Mux(
        io.state11 === State.BLNK && nd === Dirn.down,
        io.state21,
        io.state11
      )
    )
  )
  
  io.ns_state13 := Mux(
    (io.state12 === State.BLNK && nd === Dirn.right) || (io.state23 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state13 === State.BLNK && nd === Dirn.left,
      io.state12,
      Mux(
        io.state13 === State.BLNK && nd === Dirn.down,
        io.state23,
        io.state13
      )
    )
  )
  
  io.ns_state31 := Mux(
    (io.state21 === State.BLNK && nd === Dirn.down) || (io.state32 === State.BLNK && nd === Dirn.left),
    State.BLNK,
    Mux(
      io.state31 === State.BLNK && nd === Dirn.top,
      io.state21,
      Mux(
        io.state31 === State.BLNK && nd === Dirn.right,
        io.state32,
        io.state31
      )
    )
  )
  
  io.ns_state33 := Mux(
    (io.state32 === State.BLNK && nd === Dirn.right) || (io.state23 === State.BLNK && nd === Dirn.down),
    State.BLNK,
    Mux(
      io.state33 === State.BLNK && nd === Dirn.left,
      io.state32,
      Mux(
        io.state33 === State.BLNK && nd === Dirn.top,
        io.state23,
        io.state33
      )
    )
  )
  
  // Edge squares logic
  io.ns_state12 := Mux(
    (io.state11 === State.BLNK && nd === Dirn.right) || 
    (io.state13 === State.BLNK && nd === Dirn.left) ||
    (io.state22 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state12 === State.BLNK && nd === Dirn.left,
      io.state11,
      Mux(
        io.state12 === State.BLNK && nd === Dirn.right,
        io.state13,
        Mux(
          io.state12 === State.BLNK && nd === Dirn.down,
          io.state22,
          io.state12
        )
      )
    )
  )
  
  io.ns_state21 := Mux(
    (io.state11 === State.BLNK && nd === Dirn.down) || 
    (io.state22 === State.BLNK && nd === Dirn.left) ||
    (io.state31 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state21 === State.BLNK && nd === Dirn.top,
      io.state11,
      Mux(
        io.state21 === State.BLNK && nd === Dirn.right,
        io.state22,
        Mux(
          io.state21 === State.BLNK && nd === Dirn.down,
          io.state31,
          io.state21
        )
      )
    )
  )
  
  io.ns_state23 := Mux(
    (io.state13 === State.BLNK && nd === Dirn.down) || 
    (io.state22 === State.BLNK && nd === Dirn.right) ||
    (io.state33 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state23 === State.BLNK && nd === Dirn.left,
      io.state22,
      Mux(
        io.state23 === State.BLNK && nd === Dirn.top,
        io.state13,
        Mux(
          io.state23 === State.BLNK && nd === Dirn.down,
          io.state33,
          io.state23
        )
      )
    )
  )
  
  io.ns_state32 := Mux(
    (io.state31 === State.BLNK && nd === Dirn.right) || 
    (io.state22 === State.BLNK && nd === Dirn.down) ||
    (io.state33 === State.BLNK && nd === Dirn.left),
    State.BLNK,
    Mux(
      io.state32 === State.BLNK && nd === Dirn.left,
      io.state31,
      Mux(
        io.state32 === State.BLNK && nd === Dirn.right,
        io.state33,
        Mux(
          io.state32 === State.BLNK && nd === Dirn.top,
          io.state22,
          io.state32
        )
      )
    )
  )
  
  // Center square logic
  io.ns_state22 := Mux(
    (io.state12 === State.BLNK && nd === Dirn.down) || 
    (io.state21 === State.BLNK && nd === Dirn.right) ||
    (io.state23 === State.BLNK && nd === Dirn.left) ||
    (io.state32 === State.BLNK && nd === Dirn.top),
    State.BLNK,
    Mux(
      io.state22 === State.BLNK && nd === Dirn.left,
      io.state21,
      Mux(
        io.state22 === State.BLNK && nd === Dirn.right,
        io.state23,
        Mux(
          io.state22 === State.BLNK && nd === Dirn.top,
          io.state12,
          Mux(
            io.state22 === State.BLNK && nd === Dirn.down,
            io.state32,
            io.state22
          )
        )
      )
    )
  )
}

// Top-level game module
class Game extends Module {
  val io = IO(new Bundle {
    // Output all states to preserve the design
    val state11 = Output(UInt(4.W))
    val state12 = Output(UInt(4.W))
    val state13 = Output(UInt(4.W))
    val state21 = Output(UInt(4.W))
    val state22 = Output(UInt(4.W))
    val state23 = Output(UInt(4.W))
    val state31 = Output(UInt(4.W))
    val state32 = Output(UInt(4.W))
    val state33 = Output(UInt(4.W))
  })
  
  // Initial states
  val init11 = State.BLNK
  val init12 = State.one
  val init13 = State.two
  val init21 = State.three
  val init22 = State.four
  val init23 = State.five
  val init31 = State.six
  val init32 = State.seven
  val init33 = State.eight
  
  // Next state wires
  val ns_state11 = Wire(UInt(4.W))
  val ns_state12 = Wire(UInt(4.W))
  val ns_state13 = Wire(UInt(4.W))
  val ns_state21 = Wire(UInt(4.W))
  val ns_state22 = Wire(UInt(4.W))
  val ns_state23 = Wire(UInt(4.W))
  val ns_state31 = Wire(UInt(4.W))
  val ns_state32 = Wire(UInt(4.W))
  val ns_state33 = Wire(UInt(4.W))
  
  // Current state wires
  val state11 = Wire(UInt(4.W))
  val state12 = Wire(UInt(4.W))
  val state13 = Wire(UInt(4.W))
  val state21 = Wire(UInt(4.W))
  val state22 = Wire(UInt(4.W))
  val state23 = Wire(UInt(4.W))
  val state31 = Wire(UInt(4.W))
  val state32 = Wire(UInt(4.W))
  val state33 = Wire(UInt(4.W))
  
  // Instantiate square modules
  val A11 = Module(new Square)
  A11.io.init := init11
  A11.io.in := ns_state11
  state11 := A11.io.out
  
  val A12 = Module(new Square)
  A12.io.init := init12
  A12.io.in := ns_state12
  state12 := A12.io.out
  
  val A13 = Module(new Square)
  A13.io.init := init13
  A13.io.in := ns_state13
  state13 := A13.io.out
  
  val A21 = Module(new Square)
  A21.io.init := init21
  A21.io.in := ns_state21
  state21 := A21.io.out
  
  val A22 = Module(new Square)
  A22.io.init := init22
  A22.io.in := ns_state22
  state22 := A22.io.out
  
  val A23 = Module(new Square)
  A23.io.init := init23
  A23.io.in := ns_state23
  state23 := A23.io.out
  
  val A31 = Module(new Square)
  A31.io.init := init31
  A31.io.in := ns_state31
  state31 := A31.io.out
  
  val A32 = Module(new Square)
  A32.io.init := init32
  A32.io.in := ns_state32
  state32 := A32.io.out
  
  val A33 = Module(new Square)
  A33.io.init := init33
  A33.io.in := ns_state33
  state33 := A33.io.out
  
  // Instantiate next state function module
  val B = Module(new Nsfunction)
  B.io.state11 := state11
  B.io.state12 := state12
  B.io.state13 := state13
  B.io.state21 := state21
  B.io.state22 := state22
  B.io.state23 := state23
  B.io.state31 := state31
  B.io.state32 := state32
  B.io.state33 := state33
  
  ns_state11 := B.io.ns_state11
  ns_state12 := B.io.ns_state12
  ns_state13 := B.io.ns_state13
  ns_state21 := B.io.ns_state21
  ns_state22 := B.io.ns_state22
  ns_state23 := B.io.ns_state23
  ns_state31 := B.io.ns_state31
  ns_state32 := B.io.ns_state32
  ns_state33 := B.io.ns_state33
  
  // Connect outputs
  io.state11 := state11
  io.state12 := state12
  io.state13 := state13
  io.state21 := state21
  io.state22 := state22
  io.state23 := state23
  io.state31 := state31
  io.state32 := state32
  io.state33 := state33
}

object VerilogGenerator extends App {
  emitVerilog(new Game(), args)
}