package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
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
  val random_push = Input(UInt(4.W))
  val random = Input(UInt(1.W))
  val r_stop = Input(UInt(1.W))
  val init = Input(UInt(2.W))
}

class MainControlIO extends Bundle {
  val inc = Input(Vec(2, Bool()))
  val dec = Input(Vec(2, Bool()))
  val stop_next = Output(Vec(2, Bool()))
  val continue = Output(Vec(2, Bool()))
  val random_up = Input(UInt(4.W))
  val random_down = Input(UInt(4.W))
  val init1 = Input(UInt(2.W))
  val init2 = Input(UInt(2.W))
  val locations = Output(Vec(2, UInt(2.W)))
}

class MainIO extends Bundle {
  val random_up = Input(UInt(4.W))
  val random_down = Input(UInt(4.W))
  val random = Input(UInt(2.W))
  val r_stop = Input(UInt(2.W))
  val random_push1 = Input(UInt(4.W))
  val init11 = Input(UInt(2.W))
  val random_push2 = Input(UInt(4.W))
  val init22 = Input(UInt(2.W))
  
  // Additional outputs to preserve design
  val e1_inc = Output(Bool())
  val e1_dec = Output(Bool())
  val e2_inc = Output(Bool())
  val e2_dec = Output(Bool())
  val locations = Output(Vec(2, UInt(2.W)))
}

class Elevator extends Module {
  val io = IO(new ElevatorIO())
  
  val buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Compute bottom and top arrays
  val bottom = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  bottom(0) := buttons(0) === OnOff.ON
  bottom(1) := bottom(0) || (buttons(1) === OnOff.ON)
  bottom(2) := bottom(1) || (buttons(2) === OnOff.ON)
  bottom(3) := bottom(2) || (buttons(3) === OnOff.ON)
  
  top(2) := buttons(3) === OnOff.ON
  top(1) := top(2) || (buttons(2) === OnOff.ON)
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below := 
    ((location === 3.U) && bottom(3)) ||
    ((location === 2.U) && bottom(2)) ||
    ((location === 1.U) && bottom(1))
    
  button_above := 
    ((location === 0.U) && top(0)) ||
    ((location === 1.U) && top(1)) ||
    ((location === 2.U) && top(2))
  
  // Button management and random pushes
  for (i <- 0 until 4) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record a request to stop at the next floor
  when(io.stop_next) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Door scheduling
  when(door =/= DoorState.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door operation
  switch(door) {
    is(DoorState.CLOSED) {
      when(open_next && movement === Movement.STOPPED) {
        door := DoorState.OPENING
      }
    }
    is(DoorState.OPENING) {
      when(io.random.asBool) {
        door := DoorState.OPEN
      }
    }
    is(DoorState.OPEN) {
      when(io.random.asBool) {
        door := DoorState.CLOSING
      }
    }
    is(DoorState.CLOSING) {
      when(io.random.asBool) {
        door := DoorState.CLOSED
      }
    }
  }
  
  // Movement control
  val start_moving = Wire(Bool())
  val stop_moving = Wire(Bool())
  
  start_moving := (io.continue || (button_above && direction === Direction.UP)) || 
                  (button_below && direction === Direction.DOWN)
  stop_moving := io.r_stop.asBool && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  when(door === DoorState.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(door === DoorState.CLOSED && start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is(Movement.MOVING) {
        when(stop_moving) {
          movement := Movement.STOPPED
          when(direction === Direction.UP) {
            location := location + 1.U
          }
          when(direction === Direction.DOWN) {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Direction control
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
  
  when(location === 3.U) { direction := Direction.DOWN }
  when(location === 0.U) { direction := Direction.UP }
}

class MainControl extends Module {
  val io = IO(new MainControlIO())
  
  val locations = RegInit(VecInit(io.init1, io.init2))
  val up_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val direction = RegInit(VecInit(Direction.UP, Direction.UP))
  
  val buttons = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  val bottom = Wire(Vec(4, Bool()))
  val button_above = Wire(Vec(2, Bool()))
  val button_below = Wire(Vec(2, Bool()))
  
  // Compute buttons array
  for (i <- 0 until 4) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  // Compute bottom and top arrays
  bottom(0) := buttons(0)
  bottom(1) := bottom(0) || buttons(1)
  bottom(2) := bottom(1) || buttons(2)
  bottom(3) := bottom(2) || buttons(3)
  
  top(2) := buttons(3)
  top(1) := top(2) || buttons(2)
  top(0) := top(1) || buttons(1)
  
  // Elevator 1 control
  button_below(0) := 
    ((locations(0) === 3.U) && bottom(3)) ||
    ((locations(0) === 2.U) && bottom(2)) ||
    ((locations(0) === 1.U) && bottom(1))
    
  button_above(0) := 
    ((locations(0) === 0.U) && top(0)) ||
    ((locations(0) === 1.U) && top(1)) ||
    ((locations(0) === 2.U) && top(2))
    
  io.continue(0) := (button_above(0) && direction(0) === Direction.UP) ||
                    (button_below(0) && direction(0) === Direction.DOWN)
                    
  io.stop_next(0) := Mux(
    (locations(0) =/= 3.U) && (direction(0) === Direction.UP),
    Mux(up_floor_buttons(locations(0) + 1.U) === OnOff.ON, true.B, false.B),
    Mux(
      (locations(0) =/= 0.U) && (direction(0) === Direction.DOWN),
      Mux(down_floor_buttons(locations(0) - 1.U) === OnOff.ON, true.B, false.B),
      false.B
    )
  )
  
  // Elevator 2 control
  button_below(1) := 
    ((locations(1) === 3.U) && bottom(3)) ||
    ((locations(1) === 2.U) && bottom(2)) ||
    ((locations(1) === 1.U) && bottom(1))
    
  button_above(1) := 
    ((locations(1) === 0.U) && top(0)) ||
    ((locations(1) === 1.U) && top(1)) ||
    ((locations(1) === 2.U) && top(2))
    
  io.continue(1) := (button_above(1) && direction(1) === Direction.UP) ||
                    (button_below(1) && direction(1) === Direction.DOWN)
                    
  io.stop_next(1) := Mux(
    (locations(1) =/= 3.U) && (direction(1) === Direction.UP),
    Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, true.B, false.B),
    Mux(
      (locations(1) =/= 0.U) && (direction(1) === Direction.DOWN),
      Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, true.B, false.B),
      false.B
    )
  )
  
  // Random button pushes
  for (i <- 0 until 4) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  for (i <- 0 until 2) {
    when((locations(i) =/= 3.U) && (direction(i) === Direction.UP)) {
      when(up_floor_buttons(locations(i) + 1.U) === OnOff.ON) {
        up_floor_buttons(locations(i) + 1.U) := OnOff.OFF
      }
    }
    when((locations(i) =/= 0.U) && (direction(i) === Direction.DOWN)) {
      when(down_floor_buttons(locations(i) - 1.U) === OnOff.ON) {
        down_floor_buttons(locations(i) - 1.U) := OnOff.OFF
      }
    }
  }
  
  // Update locations and directions
  for (i <- 0 until 2) {
    when(locations(i) === 3.U) { direction(i) := Direction.DOWN }
    when(locations(i) === 0.U) { direction(i) := Direction.UP }
    when(io.inc(i)) {
      locations(i) := locations(i) + 1.U
      direction(i) := Direction.UP
    }
    when(io.dec(i)) {
      locations(i) := locations(i) - 1.U
      direction(i) := Direction.DOWN
    }
  }
  
  // Output locations
  io.locations := locations
}

class Main extends Module {
  val io = IO(new MainIO())
  
  val stop_next = Wire(Vec(2, Bool()))
  val inc = Wire(Vec(2, Bool()))
  val dec = Wire(Vec(2, Bool()))
  val continue = Wire(Vec(2, Bool()))
  
  val init1 = Wire(UInt(2.W))
  val init2 = Wire(UInt(2.W))
  
  // Choose initial state for each elevator
  init1 := Mux(io.init11 >= 4.U, 3.U, io.init11)
  init2 := Mux(io.init22 >= 4.U, 3.U, io.init22)
  
  val e1 = Module(new Elevator())
  val e2 = Module(new Elevator())
  val main_control = Module(new MainControl())
  
  // Connect elevator 1
  e1.io.stop_next := stop_next(0)
  e1.io.continue := continue(0)
  e1.io.random_push := io.random_push1
  e1.io.random := io.random(0)
  e1.io.r_stop := io.r_stop(0)
  e1.io.init := init1
  
  // Connect elevator 2
  e2.io.stop_next := stop_next(1)
  e2.io.continue := continue(1)
  e2.io.random_push := io.random_push2
  e2.io.random := io.random(1)
  e2.io.r_stop := io.r_stop(1)
  e2.io.init := init2
  
  // Connect main control
  main_control.io.inc := inc
  main_control.io.dec := dec
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  main_control.io.init2 := init2
  
  stop_next := main_control.io.stop_next
  continue := main_control.io.continue
  inc(0) := e1.io.inc
  dec(0) := e1.io.dec
  inc(1) := e2.io.inc
  dec(1) := e2.io.dec
  
  // Additional outputs to preserve design
  io.e1_inc := e1.io.inc
  io.e1_dec := e1.io.dec
  io.e2_inc := e2.io.inc
  io.e2_dec := e2.io.dec
  io.locations := main_control.io.locations
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}