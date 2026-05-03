package llmverify

import chisel3._
import chisel3.util._

// Enum definitions using plain UInt
object Direction {
  val UP = 0.U(1.W)
  val DOWN = 1.U(1.W)
}

object Movement {
  val STOPPED = 0.U(1.W)
  val MOVING = 1.U(1.W)
}

object Door {
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
  val random = Input(UInt(2.W))
  val r_stop = Input(UInt(2.W))
  val init = Input(UInt(2.W))
  // Debug outputs to preserve signals
  val location_debug = Output(UInt(2.W))
  val direction_debug = Output(UInt(1.W))
  val movement_debug = Output(UInt(1.W))
  val door_debug = Output(UInt(2.W))
  val buttons_debug = Output(UInt(3.W))
}

class Elevator extends Module {
  val io = IO(new ElevatorIO)
  
  // State registers
  val buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(Door.OPEN)
  val open_next = RegInit(false.B)
  
  // Combinational logic for buttons
  val bottom = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  bottom(0) := false.B
  bottom(1) := buttons(0) === OnOff.ON
  bottom(2) := bottom(1) || (buttons(1) === OnOff.ON)
  top(2) := false.B
  top(1) := buttons(2) === OnOff.ON
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  button_below := (location === 2.U && bottom(2)) || (location === 1.U && bottom(1))
  button_above := (location === 0.U && top(0)) || (location === 2.U && top(1))
  
  // Button handling - turn off button for current floor, randomly push others
  for (i <- 0 until 3) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record stop_next request
  when(io.stop_next) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule door to open
  when(door =/= Door.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door state machine
  switch(door) {
    is(Door.CLOSED) {
      when(open_next && movement === Movement.STOPPED) {
        door := Door.OPENING
      }
    }
    is(Door.OPENING) {
      when(io.random(0)) {
        door := Door.OPEN
      }
    }
    is(Door.OPEN) {
      when(io.random(0)) {
        door := Door.CLOSING
      }
    }
    is(Door.CLOSING) {
      when(io.random(0)) {
        door := Door.CLOSED
      }
    }
  }
  
  // Movement control
  val start_moving = Wire(Bool())
  val stop_moving = Wire(Bool())
  start_moving := io.continue || 
                  (button_above && direction === Direction.UP) || 
                  (button_below && direction === Direction.DOWN)
  stop_moving := io.r_stop(0) && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  // Movement state machine
  when(door === Door.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is(Movement.MOVING) {
        when(stop_moving) {
          movement := Movement.STOPPED
          when(direction === Direction.UP) {
            location := location + 1.U
          }.otherwise {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Direction logic
  switch(direction) {
    is(Direction.UP) {
      when(!button_above && !io.continue) {
        direction := Direction.DOWN
      }
    }
    is(Direction.DOWN) {
      when(!button_below && !io.continue) {
        direction := Direction.UP
      }
    }
  }
  when(location === 2.U) {
    direction := Direction.DOWN
  }
  when(location === 0.U) {
    direction := Direction.UP
  }
  
  // Debug outputs
  io.location_debug := location
  io.direction_debug := direction
  io.movement_debug := movement
  io.door_debug := door
  io.buttons_debug := Cat(buttons(2), buttons(1), buttons(0))
}

class MainControlIO extends Bundle {
  val inc = Input(Vec(4, Bool())) // 1-indexed, so we use 4 elements (0 unused)
  val dec = Input(Vec(4, Bool()))
  val stop_next = Output(Vec(4, Bool()))
  val continue = Output(Vec(4, Bool()))
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val init1 = Input(UInt(2.W))
  val init2 = Input(UInt(2.W))
  // Debug outputs
  val locations_debug = Output(Vec(4, UInt(2.W)))
  val up_buttons_debug = Output(UInt(3.W))
  val down_buttons_debug = Output(UInt(3.W))
  val direction_debug = Output(Vec(4, UInt(1.W)))
}

class MainControl extends Module {
  val io = IO(new MainControlIO)
  
  // State registers - using 4-element Vecs to match 1-indexed Verilog
  val locations = RegInit(VecInit(Seq(0.U, io.init1, io.init2, 0.U))) // indices 0,1,2,3
  val up_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val direction = RegInit(VecInit(Seq(0.U, Direction.UP, Direction.UP, 0.U))) // indices 0,1,2,3
  
  // Initialize unused indices to prevent compilation errors
  io.stop_next(0) := false.B
  io.continue(0) := false.B
  
  // Button states
  val buttons = Wire(Vec(3, Bool()))
  for (i <- 0 until 3) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  val bottom = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  bottom(0) := false.B
  bottom(1) := buttons(0)
  bottom(2) := bottom(1) || buttons(1)
  top(2) := false.B
  top(1) := buttons(2)
  top(0) := top(1) || buttons(1)
  
  // Elevator 1 logic (index 1)
  val button_below_1 = Wire(Bool())
  val button_above_1 = Wire(Bool())
  button_below_1 := ((locations(1) === 2.U) && bottom(2)) || (locations(1) === 1.U && bottom(1))
  button_above_1 := ((locations(1) === 0.U) && top(0)) || ((locations(1) === 2.U) && top(1))
  io.continue(1) := button_above_1 && (direction(1) === Direction.UP) ||
                     button_below_1 && (direction(1) === Direction.DOWN)
  io.stop_next(1) := Mux(locations(1) =/= 2.U && (direction(1) === Direction.UP),
                         Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, true.B, false.B),
                         Mux(locations(1) =/= 0.U && (direction(1) === Direction.DOWN),
                             Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, true.B, false.B),
                             false.B))
  
  // Elevator 2 logic (index 2)
  val button_below_2 = Wire(Bool())
  val button_above_2 = Wire(Bool())
  button_below_2 := ((locations(2) === 2.U) && bottom(2)) || (locations(2) === 1.U && bottom(1))
  button_above_2 := ((locations(2) === 0.U) && top(0)) || ((locations(2) === 2.U) && top(1))
  io.continue(2) := button_above_2 && (direction(2) === Direction.UP) ||
                     button_below_2 && (direction(2) === Direction.DOWN)
  io.stop_next(2) := Mux(locations(2) =/= 2.U && (direction(2) === Direction.UP),
                         Mux(up_floor_buttons(locations(2) + 1.U) === OnOff.ON, true.B, false.B),
                         Mux(locations(2) =/= 0.U && (direction(2) === Direction.DOWN),
                             Mux(down_floor_buttons(locations(2) - 1.U) === OnOff.ON, true.B, false.B),
                             false.B))
  
  // Initialize unused index 3
  io.continue(3) := false.B
  io.stop_next(3) := false.B
  
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
  for (i <- 1 to 2) {
    when(locations(i) =/= 2.U && (direction(i) === Direction.UP)) {
      when(up_floor_buttons(locations(i) + 1.U) === OnOff.ON) {
        up_floor_buttons(locations(i) + 1.U) := OnOff.OFF
      }
    }
    when(locations(i) =/= 0.U && (direction(i) === Direction.DOWN)) {
      when(down_floor_buttons(locations(i) - 1.U) === OnOff.ON) {
        down_floor_buttons(locations(i) - 1.U) := OnOff.OFF
      }
    }
  }
  
  // Update locations and directions
  for (i <- 1 to 2) {
    when(locations(i) === 2.U) {
      direction(i) := Direction.DOWN
    }
    when(locations(i) === 0.U) {
      direction(i) := Direction.UP
    }
    when(io.inc(i)) {
      locations(i) := locations(i) + 1.U
      direction(i) := Direction.UP
    }
    when(io.dec(i)) {
      locations(i) := locations(i) - 1.U
      direction(i) := Direction.DOWN
    }
  }
  
  // Debug outputs
  io.locations_debug := locations
  io.up_buttons_debug := Cat(up_floor_buttons(2), up_floor_buttons(1), up_floor_buttons(0))
  io.down_buttons_debug := Cat(down_floor_buttons(2), down_floor_buttons(1), down_floor_buttons(0))
  io.direction_debug := direction
}

class MainIO extends Bundle {
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val random = Input(UInt(2.W))
  val r_stop = Input(UInt(2.W))
  val random_push1 = Input(UInt(3.W))
  val init11 = Input(UInt(2.W))
  val random_push2 = Input(UInt(3.W))
  val init22 = Input(UInt(2.W))
  // Debug outputs to preserve all signals
  val e1_location = Output(UInt(2.W))
  val e1_direction = Output(UInt(1.W))
  val e1_movement = Output(UInt(1.W))
  val e1_door = Output(UInt(2.W))
  val e1_buttons = Output(UInt(3.W))
  val e2_location = Output(UInt(2.W))
  val e2_direction = Output(UInt(1.W))
  val e2_movement = Output(UInt(1.W))
  val e2_door = Output(UInt(2.W))
  val e2_buttons = Output(UInt(3.W))
  val locations = Output(Vec(4, UInt(2.W)))
  val up_buttons = Output(UInt(3.W))
  val down_buttons = Output(UInt(3.W))
  val directions = Output(Vec(4, UInt(1.W)))
}

class Main extends Module {
  val io = IO(new MainIO)
  
  // Choose initial state for each elevator
  val init1 = Mux(io.init11 >= 3.U, 2.U, io.init11)
  val init2 = Mux(io.init22 >= 3.U, 2.U, io.init22)
  
  // Wires for connections - using 4-element Vecs to match 1-indexed Verilog
  val stop_next = Wire(Vec(4, Bool()))
  val inc = Wire(Vec(4, Bool()))
  val dec = Wire(Vec(4, Bool()))
  val continue = Wire(Vec(4, Bool()))
  
  // Initialize unused indices to prevent compilation errors
  inc(0) := false.B
  dec(0) := false.B
  inc(3) := false.B
  dec(3) := false.B
  
  // Instantiate elevators
  val e1 = Module(new Elevator())
  e1.io.stop_next := stop_next(1)
  e1.io.continue := continue(1)
  e1.io.random_push := io.random_push1
  e1.io.random := io.random
  e1.io.r_stop := io.r_stop
  e1.io.init := init1
  inc(1) := e1.io.inc
  dec(1) := e1.io.dec
  
  val e2 = Module(new Elevator())
  e2.io.stop_next := stop_next(2)
  e2.io.continue := continue(2)
  e2.io.random_push := io.random_push2
  e2.io.random := io.random
  e2.io.r_stop := io.r_stop
  e2.io.init := init2
  inc(2) := e2.io.inc
  dec(2) := e2.io.dec
  
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
  io.e1_location := e1.io.location_debug
  io.e1_direction := e1.io.direction_debug
  io.e1_movement := e1.io.movement_debug
  io.e1_door := e1.io.door_debug
  io.e1_buttons := e1.io.buttons_debug
  io.e2_location := e2.io.location_debug
  io.e2_direction := e2.io.direction_debug
  io.e2_movement := e2.io.movement_debug
  io.e2_door := e2.io.door_debug
  io.e2_buttons := e2.io.buttons_debug
  io.locations := main_control.io.locations_debug
  io.up_buttons := main_control.io.up_buttons_debug
  io.down_buttons := main_control.io.down_buttons_debug
  io.directions := main_control.io.direction_debug
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}