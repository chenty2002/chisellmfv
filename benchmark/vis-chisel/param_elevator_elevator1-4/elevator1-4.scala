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

// Elevator module
class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(Bool())
    val continue = Input(Bool())
    val random_push = Input(UInt(4.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val init = Input(UInt(2.W))
    val inc = Output(Bool())
    val dec = Output(Bool())
    val location = Output(UInt(2.W))
    val door_state = Output(UInt(2.W))
    val movement_state = Output(UInt(1.W))
    val dir_val = Output(UInt(1.W))
  })
  
  // Internal registers
  val buttons = RegInit(VecInit(Seq.fill(4)(0.U(1.W))))
  val location = RegInit(io.init)
  val dir_val = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(Door.OPEN)
  val open_next = RegInit(false.B)
  
  // Button logic
  val bottom = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  bottom(0) := buttons(0) === OnOff.ON
  bottom(1) := bottom(0) || (buttons(1) === OnOff.ON)
  bottom(2) := bottom(1) || (buttons(2) === OnOff.ON)
  bottom(3) := bottom(2) || (buttons(3) === OnOff.ON)
  
  top(2) := buttons(3) === OnOff.ON
  top(1) := top(2) || (buttons(2) === OnOff.ON)
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = (location === 3.U && bottom(3)) ||
                     (location === 2.U && bottom(2)) ||
                     (location === 1.U && bottom(1))
  
  val button_above = (location === 0.U && top(0)) ||
                     (location === 1.U && top(1)) ||
                     (location === 2.U && top(2))
  
  // Button management
  when(io.random_push.orR) {
    for (i <- 0 until 4) {
      when(i.U === location) {
        buttons(i) := OnOff.OFF
      }.elsewhen(io.random_push(i)) {
        buttons(i) := OnOff.ON
      }
    }
  }
  
  // Record stop request
  when(io.stop_next) {
    when(dir_val === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule door opening
  when(door =/= Door.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (dir_val === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (dir_val === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
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
      when(io.random) {
        door := Door.OPEN
      }
    }
    is(Door.OPEN) {
      when(io.random) {
        door := Door.CLOSING
      }
    }
    is(Door.CLOSING) {
      when(io.random) {
        door := Door.CLOSED
      }
    }
  }
  
  // Movement logic
  val start_moving = io.continue || 
                     (button_above && dir_val === Direction.UP) || 
                     (button_below && dir_val === Direction.DOWN)
  val stop_moving = io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (dir_val === Direction.UP)
  io.dec := stop_moving && (dir_val === Direction.DOWN)
  
  when(door === Door.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(door === Door.CLOSED && start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is(Movement.MOVING) {
        when(stop_moving) {
          movement := Movement.STOPPED
          when(dir_val === Direction.UP) {
            location := location + 1.U
          }
          when(dir_val === Direction.DOWN) {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Direction logic
  switch(dir_val) {
    is(Direction.UP) {
      when(!button_above && !io.continue) {
        dir_val := Direction.DOWN
      }
    }
    is(Direction.DOWN) {
      when(!button_below && !io.continue) {
        dir_val := Direction.UP
      }
    }
  }
  
  when(location === 3.U) {
    dir_val := Direction.DOWN
  }
  when(location === 0.U) {
    dir_val := Direction.UP
  }
  
  // Outputs
  io.location := location
  io.door_state := door
  io.movement_state := movement
  io.dir_val := dir_val
}

// Main control module
class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(UInt(1.W))
    val dec = Input(UInt(1.W))
    val stop_next = Output(UInt(1.W))
    val continue = Output(UInt(1.W))
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val init1 = Input(UInt(2.W))
    val locations = Output(UInt(2.W))
    val dir_val = Output(UInt(1.W))
  })
  
  // Internal registers
  val locations = RegInit(io.init1)
  val up_floor_buttons = RegInit(VecInit(Seq.fill(4)(0.U(1.W))))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(4)(0.U(1.W))))
  val dir_val = RegInit(Direction.UP)
  
  // Button logic
  val buttons = Wire(Vec(4, Bool()))
  for (i <- 0 until 4) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  val bottom = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  bottom(0) := buttons(0)
  bottom(1) := bottom(0) || buttons(1)
  bottom(2) := bottom(1) || buttons(2)
  bottom(3) := bottom(2) || buttons(3)
  
  top(2) := buttons(3)
  top(1) := top(2) || buttons(2)
  top(0) := top(1) || buttons(1)
  
  val button_below = (locations === 3.U && bottom(3)) ||
                     (locations === 2.U && bottom(2)) ||
                     (locations === 1.U && bottom(1))
  
  val button_above = (locations === 0.U && top(0)) ||
                     (locations === 1.U && top(1)) ||
                     (locations === 2.U && top(2))
  
  // Control signals
  io.continue := (button_above && dir_val === Direction.UP) ||
                 (button_below && dir_val === Direction.DOWN)
  
  io.stop_next := Mux(locations =/= 3.U && dir_val === Direction.UP,
                     up_floor_buttons(locations + 1.U) === OnOff.ON,
                     Mux(locations =/= 0.U && dir_val === Direction.DOWN,
                         down_floor_buttons(locations - 1.U) === OnOff.ON,
                         0.U))
  
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
  when(locations =/= 3.U && dir_val === Direction.UP) {
    when(up_floor_buttons(locations + 1.U) === OnOff.ON) {
      up_floor_buttons(locations + 1.U) := OnOff.OFF
    }
  }
  when(locations =/= 0.U && dir_val === Direction.DOWN) {
    when(down_floor_buttons(locations - 1.U) === OnOff.ON) {
      down_floor_buttons(locations - 1.U) := OnOff.OFF
    }
  }
  
  // Update locations and directions
  when(locations === 3.U) {
    dir_val := Direction.DOWN
  }
  when(locations === 0.U) {
    dir_val := Direction.UP
  }
  
  when(io.inc(0)) {
    locations := locations + 1.U
    dir_val := Direction.UP
  }
  when(io.dec(0)) {
    locations := locations - 1.U
    dir_val := Direction.DOWN
  }
  
  // Outputs
  io.locations := locations
  io.dir_val := dir_val
}

// Main module
class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val random_push1 = Input(UInt(4.W))
    val init11 = Input(UInt(2.W))
    // Additional outputs for verification
    val elevator_location = Output(UInt(2.W))
    val control_location = Output(UInt(2.W))
    val door_state = Output(UInt(2.W))
    val movement_state = Output(UInt(1.W))
    val elevator_dir = Output(UInt(1.W))
    val control_dir = Output(UInt(1.W))
    val stop_next = Output(UInt(1.W))
    val continue = Output(UInt(1.W))
  })
  
  val init1 = io.init11
  
  // Internal signals
  val stop_next = Wire(UInt(1.W))
  val inc = Wire(UInt(1.W))
  val dec = Wire(UInt(1.W))
  val continue = Wire(UInt(1.W))
  
  // Instantiate elevator
  val elevator = Module(new Elevator())
  elevator.io.stop_next := stop_next(0)
  elevator.io.continue := continue(0)
  elevator.io.random_push := io.random_push1
  elevator.io.random := io.random(0)
  elevator.io.r_stop := io.r_stop(0)
  elevator.io.init := init1
  
  // Instantiate main control
  val main_control = Module(new MainControl())
  main_control.io.inc := inc
  main_control.io.dec := dec
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  
  // Connect signals
  stop_next := main_control.io.stop_next
  continue := main_control.io.continue
  inc := elevator.io.inc
  dec := elevator.io.dec
  
  // Outputs for verification
  io.elevator_location := elevator.io.location
  io.control_location := main_control.io.locations
  io.door_state := elevator.io.door_state
  io.movement_state := elevator.io.movement_state
  io.elevator_dir := elevator.io.dir_val
  io.control_dir := main_control.io.dir_val
  io.stop_next := stop_next
  io.continue := continue
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}