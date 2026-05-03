package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object Direction extends ChiselEnum {
  val UP = Value
  val DOWN = Value
}

object Movement extends ChiselEnum {
  val STOPPED = Value
  val MOVING = Value
}

object DoorState extends ChiselEnum {
  val OPEN = Value
  val OPENING = Value
  val CLOSED = Value
  val CLOSING = Value
}

object OnOff extends ChiselEnum {
  val ON = Value
  val OFF = Value
}

class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val random_push1 = Input(UInt(4.W))
    val init11 = Input(UInt(2.W))
    // Add outputs to preserve the design
    val stop_next_out = Output(UInt(1.W))
    val inc_out = Output(UInt(1.W))
    val dec_out = Output(UInt(1.W))
    val continue_out = Output(UInt(1.W))
    val location_out = Output(UInt(2.W))
    val door_state_out = Output(DoorState())
  })
  
  val stop_next = Wire(UInt(1.W))
  val inc = Wire(UInt(1.W))
  val dec = Wire(UInt(1.W))
  val continue = Wire(UInt(1.W))
  
  val init1 = io.init11
  
  val elevator1 = Module(new Elevator())
  elevator1.io.stop_next := stop_next
  elevator1.io.continue := continue
  elevator1.io.random_push := io.random_push1
  elevator1.io.random := io.random
  elevator1.io.r_stop := io.r_stop
  elevator1.io.init := init1
  
  inc := elevator1.io.inc
  dec := elevator1.io.dec
  
  val mainControl = Module(new MainControl())
  mainControl.io.inc := inc
  mainControl.io.dec := dec
  mainControl.io.random_up := io.random_up
  mainControl.io.random_down := io.random_down
  mainControl.io.init1 := init1
  
  stop_next := mainControl.io.stop_next
  continue := mainControl.io.continue
  
  // Connect outputs for preservation
  io.stop_next_out := stop_next
  io.inc_out := inc
  io.dec_out := dec
  io.continue_out := continue
  io.location_out := elevator1.io.location
  io.door_state_out := elevator1.io.door_state
}

class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(UInt(1.W))
    val dec = Input(UInt(1.W))
    val stop_next = Output(UInt(1.W))
    val continue = Output(UInt(1.W))
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val init1 = Input(UInt(2.W))
    // Add outputs to preserve the design
    val locations_out = Output(UInt(2.W))
    val up_buttons_out = Output(Vec(4, OnOff()))
    val down_buttons_out = Output(Vec(4, OnOff()))
    val direction_out = Output(Direction())
  })
  
  // Create 2-element vectors to match Verilog [1:1] indexing
  val locations = RegInit(VecInit(Seq(0.U(2.W), 0.U(2.W))))
  val up_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val direction = RegInit(VecInit(Seq(Direction.UP, Direction.UP)))
  
  // Initialize location[1] with init1
  locations(1) := io.init1
  
  // Compute buttons
  val buttons = Wire(Vec(4, Bool()))
  for (i <- 0 until 4) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  // Compute bottom and top - initialize all elements
  val bottom = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  // Initialize unused elements
  bottom(0) := false.B
  
  bottom(1) := buttons(0)
  bottom(2) := bottom(1) || buttons(1)
  bottom(3) := bottom(2) || buttons(2)
  
  top(2) := buttons(3)
  top(1) := top(2) || buttons(2)
  top(0) := top(1) || buttons(1)
  
  // Compute button_above and button_below for elevator 1
  val button_below = 
    ((locations(1) === 3.U) && bottom(3)) ||
    ((locations(1) === 2.U) && bottom(2)) ||
    ((locations(1) === 1.U) && bottom(1))
    
  val button_above =
    ((locations(1) === 0.U) && top(0)) ||
    ((locations(1) === 1.U) && top(1)) ||
    ((locations(1) === 2.U) && top(2))
  
  io.continue := 
    (button_above && direction(1) === Direction.UP) ||
    (button_below && direction(1) === Direction.DOWN)
    
  io.stop_next := Mux(
    (locations(1) =/= 3.U) && (direction(1) === Direction.UP),
    Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, 1.U, 0.U),
    Mux(
      (locations(1) =/= 0.U) && (direction(1) === Direction.DOWN),
      Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, 1.U, 0.U),
      0.U
    )
  )
  
  // Randomly push floor buttons
  for (i <- 0 until 4) {
    when (io.random_up(i).asBool) {
      up_floor_buttons(i) := OnOff.ON
    }
    when (io.random_down(i).asBool) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons for elevator 1
  when ((locations(1) =/= 3.U) && (direction(1) === Direction.UP)) {
    when (up_floor_buttons(locations(1) + 1.U) === OnOff.ON) {
      up_floor_buttons(locations(1) + 1.U) := OnOff.OFF
    }
  }
  when ((locations(1) =/= 0.U) && (direction(1) === Direction.DOWN)) {
    when (down_floor_buttons(locations(1) - 1.U) === OnOff.ON) {
      down_floor_buttons(locations(1) - 1.U) := OnOff.OFF
    }
  }
  
  // Keep track of locations and directions for elevator 1
  when (locations(1) === 3.U) {
    direction(1) := Direction.DOWN
  }
  when (locations(1) === 0.U) {
    direction(1) := Direction.UP
  }
  when (io.inc.asBool) {
    locations(1) := locations(1) + 1.U
    direction(1) := Direction.UP
  }
  when (io.dec.asBool) {
    locations(1) := locations(1) - 1.U
    direction(1) := Direction.DOWN
  }
  
  // Connect outputs for preservation
  io.locations_out := locations(1)
  io.up_buttons_out := up_floor_buttons
  io.down_buttons_out := down_floor_buttons
  io.direction_out := direction(1)
}

class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(UInt(1.W))
    val continue = Input(UInt(1.W))
    val random_push = Input(UInt(4.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val init = Input(UInt(2.W))
    val inc = Output(UInt(1.W))
    val dec = Output(UInt(1.W))
    // Add outputs to preserve the design
    val location = Output(UInt(2.W))
    val door_state = Output(DoorState())
    val movement_state = Output(Movement())
    val direction_state = Output(Direction())
    val buttons_out = Output(Vec(4, OnOff()))
  })
  
  val buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Compute bottom and top for buttons - initialize all elements
  val bottom = Wire(Vec(4, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  // Initialize unused element
  bottom(0) := false.B
  
  bottom(1) := buttons(0) === OnOff.ON
  bottom(2) := bottom(1) || (buttons(1) === OnOff.ON)
  bottom(3) := bottom(2) || (buttons(2) === OnOff.ON)
  
  top(2) := buttons(3) === OnOff.ON
  top(1) := top(2) || (buttons(2) === OnOff.ON)
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = 
    (location === 3.U && bottom(3)) ||
    (location === 2.U && bottom(2)) ||
    (location === 1.U && bottom(1))
    
  val button_above =
    (location === 0.U && top(0)) ||
    (location === 1.U && top(1)) ||
    (location === 2.U && top(2))
  
  // Randomly push buttons and turn off current floor button
  for (i <- 0 until 4) {
    when (i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen (io.random_push(i).asBool) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record a request to stop at the next floor
  when (io.stop_next.asBool) {
    when (direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule the door to open at the next floor
  when (door =/= DoorState.CLOSED) {
    open_next := false.B
  }.elsewhen (movement === Movement.MOVING &&
    (io.stop_next.asBool ||
     (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
     (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door operation
  switch (door) {
    is (DoorState.CLOSED) {
      when (open_next && movement === Movement.STOPPED) {
        door := DoorState.OPENING
      }
    }
    is (DoorState.OPENING) {
      when (io.random.asBool) {
        door := DoorState.OPEN
      }
    }
    is (DoorState.OPEN) {
      when (io.random.asBool) {
        door := DoorState.CLOSING
      }
    }
    is (DoorState.CLOSING) {
      when (io.random.asBool) {
        door := DoorState.CLOSED
      }
    }
  }
  
  // Movement control
  val start_moving = (io.continue.asBool || (button_above && direction === Direction.UP)) || 
                     (button_below && direction === Direction.DOWN)
  val stop_moving = io.r_stop.asBool && (movement === Movement.MOVING)
  
  io.inc := Mux(stop_moving && (direction === Direction.UP), 1.U, 0.U)
  io.dec := Mux(stop_moving && (direction === Direction.DOWN), 1.U, 0.U)
  
  when (door === DoorState.CLOSED) {
    switch (movement) {
      is (Movement.STOPPED) {
        when (door === DoorState.CLOSED && start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is (Movement.MOVING) {
        when (stop_moving) {
          movement := Movement.STOPPED
          when (direction === Direction.UP) {
            location := location + 1.U
          }
          when (direction === Direction.DOWN) {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Determine direction of movement
  switch (direction) {
    is (Direction.UP) {
      when (!button_above && !io.continue.asBool) {
        direction := Direction.DOWN
      }
    }
    is (Direction.DOWN) {
      when (!button_below && !io.continue.asBool) {
        direction := Direction.UP
      }
    }
  }
  
  when (location === 3.U) {
    direction := Direction.DOWN
  }
  when (location === 0.U) {
    direction := Direction.UP
  }
  
  // Connect outputs for preservation
  io.location := location
  io.door_state := door
  io.movement_state := movement
  io.direction_state := direction
  io.buttons_out := buttons
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}