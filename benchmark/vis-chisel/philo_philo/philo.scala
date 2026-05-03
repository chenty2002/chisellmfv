package llmverify
import chisel3._
import chisel3.util._

class Philosopher extends Module {
  val io = IO(new Bundle {
    val go = Input(Bool())
    val left = Input(UInt(2.W))
    val right = Input(UInt(2.W))
    val init = Input(UInt(2.W))
    val out = Output(UInt(2.W))
  })
  
  val state = RegInit(io.init)
  
  io.out := state
  
  when(io.go) {
    switch(state) {
      is(0.U) {
        when(io.left =/= 2.U && io.left =/= 3.U) {
          state := 1.U
        }
      }
      is(1.U) {
        when(io.right =/= 1.U && io.right =/= 3.U) {
          state := 3.U
        }
      }
      is(3.U) {
        state := 2.U
      }
      is(2.U) {
        state := 0.U
      }
    }
  }
}

class Philo10 extends Module {
  val io = IO(new Bundle {
    val select = Input(UInt(4.W))
    // Expose all philosopher states to preserve the design
    val st0 = Output(UInt(2.W))
    val st1 = Output(UInt(2.W))
    val st2 = Output(UInt(2.W))
    val st3 = Output(UInt(2.W))
    val st4 = Output(UInt(2.W))
    val st5 = Output(UInt(2.W))
    val st6 = Output(UInt(2.W))
    val st7 = Output(UInt(2.W))
    val st8 = Output(UInt(2.W))
    val st9 = Output(UInt(2.W))
  })
  
  val selreg = RegInit(10.U(4.W))
  
  selreg := io.select
  
  // Create 10 philosopher instances in a ring
  val ph0 = Module(new Philosopher())
  val ph1 = Module(new Philosopher())
  val ph2 = Module(new Philosopher())
  val ph3 = Module(new Philosopher())
  val ph4 = Module(new Philosopher())
  val ph5 = Module(new Philosopher())
  val ph6 = Module(new Philosopher())
  val ph7 = Module(new Philosopher())
  val ph8 = Module(new Philosopher())
  val ph9 = Module(new Philosopher())
  
  // Connect philosopher 0
  ph0.io.go := selreg === 0.U
  ph0.io.left := ph9.io.out
  ph0.io.right := ph1.io.out
  ph0.io.init := 0.U
  io.st0 := ph0.io.out
  
  // Connect philosopher 1
  ph1.io.go := selreg === 1.U
  ph1.io.left := ph0.io.out
  ph1.io.right := ph2.io.out
  ph1.io.init := 0.U
  io.st1 := ph1.io.out
  
  // Connect philosopher 2
  ph2.io.go := selreg === 2.U
  ph2.io.left := ph1.io.out
  ph2.io.right := ph3.io.out
  ph2.io.init := 0.U
  io.st2 := ph2.io.out
  
  // Connect philosopher 3
  ph3.io.go := selreg === 3.U
  ph3.io.left := ph2.io.out
  ph3.io.right := ph4.io.out
  ph3.io.init := 0.U
  io.st3 := ph3.io.out
  
  // Connect philosopher 4
  ph4.io.go := selreg === 4.U
  ph4.io.left := ph3.io.out
  ph4.io.right := ph5.io.out
  ph4.io.init := 0.U
  io.st4 := ph4.io.out
  
  // Connect philosopher 5
  ph5.io.go := selreg === 5.U
  ph5.io.left := ph4.io.out
  ph5.io.right := ph6.io.out
  ph5.io.init := 0.U
  io.st5 := ph5.io.out
  
  // Connect philosopher 6
  ph6.io.go := selreg === 6.U
  ph6.io.left := ph5.io.out
  ph6.io.right := ph7.io.out
  ph6.io.init := 0.U
  io.st6 := ph6.io.out
  
  // Connect philosopher 7
  ph7.io.go := selreg === 7.U
  ph7.io.left := ph6.io.out
  ph7.io.right := ph8.io.out
  ph7.io.init := 0.U
  io.st7 := ph7.io.out
  
  // Connect philosopher 8
  ph8.io.go := selreg === 8.U
  ph8.io.left := ph7.io.out
  ph8.io.right := ph9.io.out
  ph8.io.init := 0.U
  io.st8 := ph8.io.out
  
  // Connect philosopher 9
  ph9.io.go := selreg === 9.U
  ph9.io.left := ph8.io.out
  ph9.io.right := ph0.io.out
  ph9.io.init := 0.U
  io.st9 := ph9.io.out
}

object VerilogGenerator extends App {
  emitVerilog(new Philo10(), args)
}