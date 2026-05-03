package llmverify

import chisel3._
import chisel3.util._

// Enum definitions using UInt directly
object Direction {
  val UP = 0.U(1.W)
  val DOWN = 1.U(1.W)
}

object Movement {
  val STOPPED = 0.U(1.W)
  val MOVING = 1.U(1.W)
}

object DoorState {
  val OPEN = 0.U(2.W)
  val OPENING = 1.U(2.W)
  val CLOSED = 2.U(2.W)
  val CLOSING = 3.U(2.W)
}

object OnOff {
  val ON = 1.U(1.W)
  val OFF = 0.U(1.W)
}

class ElevatorIO extends Bundle {
  val stop_next = Input(Bool())
  val inc = Output(Bool())
  val dec = Output(Bool())
  val continue = Input(Bool())
  val random_push = Input(UInt(3.W))
  val random = Input(Bool())
  val r_stop = Input(Bool())
  val init = Input(UInt(2.W))
  
  // Debug outputs to preserve signals
  val location = Output(UInt(2.W))
  val dir = Output(UInt(1.W))
  val movement = Output(UInt(1.W))
  val door = Output(UInt(2.W))
  val buttons = Output(UInt(3.W))
}

class Elevator extends Module {
  val io = IO(new ElevatorIO)
  
  // Registers - use UInt directly for buttons
  val buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val location = RegInit(io.init)
  val dir = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Combinational logic
  val bottom = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  bottom(0) := false.B
  bottom(1) := buttons(0) === OnOff.ON
  bottom(2) := bottom(1) || (buttons(1) === OnOff.ON)
  
  top(2) := false.B
  top(1) := buttons(2) === OnOff.ON
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  button_below := (location === 2.U && bottom(2)) || (location === 1.U && bottom(1))
  button_above := (location === 0.U && top(0)) || (location === 1.U && top(1))
  
  // Button handling
  when(io.random_push.orR) {
    for (i <- 0 until 3) {
      when(i.U === location) {
        buttons(i) := OnOff.OFF
      }.elsewhen(io.random_push(i)) {
        buttons(i) := OnOff.ON
      }
    }
  }
  
  // Record stop_next request
  when(io.stop_next) {
    when(dir === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule door opening
  when(door =/= DoorState.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (dir === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (dir === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door state machine
  switch(door) {
    is(DoorState.CLOSED) {
      when(open_next && movement === Movement.STOPPED) {
        door := DoorState.OPENING
      }
    }
    is(DoorState.OPENING) {
      when(io.random) {
        door := DoorState.OPEN
      }
    }
    is(DoorState.OPEN) {
      when(io.random) {
        door := DoorState.CLOSING
      }
    }
    is(DoorState.CLOSING) {
      when(io.random) {
        door := DoorState.CLOSED
      }
    }
  }
  
  // Movement logic
  val start_moving = io.continue || (button_above && dir === Direction.UP) || 
                     (button_below && dir === Direction.DOWN)
  val stop_moving = io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (dir === Direction.UP)
  io.dec := stop_moving && (dir === Direction.DOWN)
  
  when(door === DoorState.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is(Movement.MOVING) {
        when(stop_moving) {
          movement := Movement.STOPPED
          when(dir === Direction.UP) {
            location := location + 1.U
          }.otherwise {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Direction logic
  switch(dir) {
    is(Direction.UP) {
      when(!button_above && !io.continue) {
        dir := Direction.DOWN
      }
    }
    is(Direction.DOWN) {
      when(!button_below && !io.continue) {
        dir := Direction.UP
      }
    }
  }
  when(location === 2.U) {
    dir := Direction.DOWN
  }
  when(location === 0.U) {
    dir := Direction.UP
  }
  
  // Debug outputs
  io.location := location
  io.dir := dir
  io.movement := movement
  io.door := door
  io.buttons := Cat(buttons(2), buttons(1), buttons(0))
}

class MainControlIO extends Bundle {
  val inc = Input(Vec(2, Bool())) // 2 elevators
  val dec = Input(Vec(2, Bool()))
  val stop_next = Output(Vec(2, Bool()))
  val continue = Output(Vec(2, Bool()))
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val init1 = Input(UInt(2.W))
  val init2 = Input(UInt(2.W))
  
  // Debug outputs
  val locations = Output(Vec(2, UInt(2.W)))
  val up_floor_buttons = Output(UInt(3.W))
  val down_floor_buttons = Output(UInt(3.W))
  val dir = Output(Vec(2, UInt(1.W)))
}

class MainControl extends Module {
  val io = IO(new MainControlIO)
  
  // Registers - use UInt directly for buttons
  val locations = RegInit(VecInit(io.init1, io.init2)) // 2 elevators
  val up_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val dir = RegInit(VecInit(Direction.UP, Direction.UP)) // 2 elevators
  
  // Combinational logic
  val buttons = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  val bottom = Wire(Vec(3, Bool()))
  val button_above = Wire(Vec(2, Bool()))
  val button_below = Wire(Vec(2, Bool()))
  
  for (i <- 0 until 3) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  bottom(0) := false.B
  bottom(1) := buttons(0)
  bottom(2) := bottom(1) || buttons(1)
  
  top(2) := false.B
  top(1) := buttons(2)
  top(0) := top(1) || buttons(1)
  
  // Elevator 1 (index 0)
  button_below(0) := ((locations(0) === 2.U) && bottom(2)) || (locations(0) === 1.U && bottom(1))
  button_above(0) := ((locations(0) === 0.U) && top(0)) || ((locations(0) === 1.U) && top(1))
  io.continue(0) := (button_above(0) && dir(0) === Direction.UP) ||
                    (button_below(0) && dir(0) === Direction.DOWN)
  io.stop_next(0) := Mux((locations(0) =/= 2.U) && (dir(0) === Direction.UP),
                         Mux(up_floor_buttons(locations(0) + 1.U) === OnOff.ON, true.B, false.B),
                         Mux((locations(0) =/= 0.U) && (dir(0) === Direction.DOWN),
                             Mux(down_floor_buttons(locations(0) - 1.U) === OnOff.ON, true.B, false.B),
                             false.B))
  
  // Elevator 2 (index 1)
  button_below(1) := ((locations(1) === 2.U) && bottom(2)) || (locations(1) === 1.U && bottom(1))
  button_above(1) := ((locations(1) === 0.U) && top(0)) || ((locations(1) === 1.U) && top(1))
  io.continue(1) := (button_above(1) && dir(1) === Direction.UP) ||
                    (button_below(1) && dir(1) === Direction.DOWN)
  io.stop_next(1) := Mux((locations(1) =/= 2.U) && (dir(1) === Direction.UP),
                         Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, true.B, false.B),
                         Mux((locations(1) =/= 0.U) && (dir(1) === Direction.DOWN),
                             Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, true.B, false.B),
                             false.B))
  
  // Random button pushes
  for (i <- 0 until 3) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  for (i <- 0 until 2) {
    when((locations(i) =/= 2.U) && (dir(i) === Direction.UP)) {
      when(up_floor_buttons(locations(i) + 1.U) === OnOff.ON) {
        up_floor_buttons(locations(i) + 1.U) := OnOff.OFF
      }
    }
    when((locations(i) =/= 0.U) && (dir(i) === Direction.DOWN)) {
      when(down_floor_buttons(locations(i) - 1.U) === OnOff.ON) {
        down_floor_buttons(locations(i) - 1.U) := OnOff.OFF
      }
    }
  }
  
  // Update locations and directions
  for (i <- 0 until 2) {
    when(locations(i) === 2.U) {
      dir(i) := Direction.DOWN
    }
    when(locations(i) === 0.U) {
      dir(i) := Direction.UP
    }
    when(io.inc(i)) {
      locations(i) := locations(i) + 1.U
      dir(i) := Direction.UP
    }
    when(io.dec(i)) {
      locations(i) := locations(i) - 1.U
      dir(i) := Direction.DOWN
    }
  }
  
  // Debug outputs
  io.locations := locations
  io.up_floor_buttons := Cat(up_floor_buttons(2), up_floor_buttons(1), up_floor_buttons(0))
  io.down_floor_buttons := Cat(down_floor_buttons(2), down_floor_buttons(1), down_floor_buttons(0))
  io.dir := dir
}

class MainIO extends Bundle {
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val random = Input(UInt(2.W)) // 2 bits for 2 elevators
  val r_stop = Input(UInt(2.W)) // 2 bits for 2 elevators
  val random_push1 = Input(UInt(3.W))
  val init11 = Input(UInt(2.W))
  val random_push2 = Input(UInt(3.W))
  val init22 = Input(UInt(2.W))
  
  // Debug outputs to preserve all signals
  val e1_location = Output(UInt(2.W))
  val e1_dir = Output(UInt(1.W))
  val e1_movement = Output(UInt(1.W))
  val e1_door = Output(UInt(2.W))
  val e1_buttons = Output(UInt(3.W))
  val e2_location = Output(UInt(2.W))
  val e2_dir = Output(UInt(1.W))
  val e2_movement = Output(UInt(1.W))
  val e2_door = Output(UInt(2.W))
  val e2_buttons = Output(UInt(3.W))
  val locations = Output(Vec(2, UInt(2.W)))
  val up_floor_buttons = Output(UInt(3.W))
  val down_floor_buttons = Output(UInt(3.W))
  val dir = Output(Vec(2, UInt(1.W)))
}

class Main extends Module {
  val io = IO(new MainIO)
  
  // Wires
  val stop_next = Wire(Vec(2, Bool()))
  val inc = Wire(Vec(2, Bool()))
  val dec = Wire(Vec(2, Bool()))
  val continue = Wire(Vec(2, Bool()))
  
  // Initial states
  val init1 = Mux(io.init11 >= 3.U, 2.U, io.init11)
  val init2 = Mux(io.init22 >= 3.U, 2.U, io.init22)
  
  // Instantiate elevators
  val e1 = Module(new Elevator())
  e1.io.stop_next := stop_next(0)
  e1.io.continue := continue(0)
  e1.io.random_push := io.random_push1
  e1.io.random := io.random(0)
  e1.io.r_stop := io.r_stop(0)
  e1.io.init := init1
  inc(0) := e1.io.inc
  dec(0) := e1.io.dec
  
  val e2 = Module(new Elevator())
  e2.io.stop_next := stop_next(1)
  e2.io.continue := continue(1)
  e2.io.random_push := io.random_push2
  e2.io.random := io.random(1)
  e2.io.r_stop := io.r_stop(1)
  e2.io.init := init2
  inc(1) := e2.io.inc
  dec(1) := e2.io.dec
  
  // Instantiate main control
  val main_control = Module(new MainControl())
  main_control.io.inc := inc
  main_control.io.dec := dec
  stop_next := main_control.io.stop_next
  continue := main_control.io.continue
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  main_control.io.init2 := init2
  
  // Debug outputs
  io.e1_location := e1.io.location
  io.e1_dir := e1.io.dir
  io.e1_movement := e1.io.movement
  io.e1_door := e1.io.door
  io.e1_buttons := e1.io.buttons
  io.e2_location := e2.io.location
  io.e2_dir := e2.io.dir
  io.e2_movement := e2.io.movement
  io.e2_door := e2.io.door
  io.e2_buttons := e2.io.buttons
  io.locations := main_control.io.locations
  io.up_floor_buttons := main_control.io.up_floor_buttons
  io.down_floor_buttons := main_control.io.down_floor_buttons
  io.dir := main_control.io.dir
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}