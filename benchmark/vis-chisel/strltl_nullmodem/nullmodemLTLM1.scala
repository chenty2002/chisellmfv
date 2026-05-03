package llmverify
import chisel3._
import chisel3.util._

// Buechi state enumeration
object BuechiState extends ChiselEnum {
  val Init = Value(0.U)
  val n1 = Value(1.U)
  val n2 = Value(2.U)
  val n4 = Value(3.U)
  val Trap = Value(4.U)
}

// UART Transmitter Module
class UartXmt extends Module {
  val io = IO(new Bundle {
    val Shift_LdF = Input(Bool())
    val ClkEnbT = Input(Bool())
    val DataT = Input(UInt(8.W))
    val ResetF = Input(Bool())
    val Serial_OuT = Output(Bool())
    val XmitMT = Output(Bool())
  })
  
  val XmitReg = RegInit(1023.U(10.W))  // 10'b1111111111
  val Count = RegInit(9.U(4.W))
  
  when(io.ResetF === 0.U) {
    XmitReg := 1023.U
    Count := 9.U
  }.elsewhen(io.ClkEnbT === 1.U && io.Shift_LdF === 0.U && io.ResetF === 1.U) {
    XmitReg := Cat(1.U(1.W), io.DataT, 0.U(1.W))
    Count := 0.U
  }.elsewhen(io.ClkEnbT === 1.U && io.Shift_LdF === 1.U && io.ResetF === 1.U) {
    XmitReg := Cat(1.U(1.W), XmitReg(9,1))
    when(Count =/= 9.U) {
      Count := Count + 1.U
    }
  }
  
  io.Serial_OuT := XmitReg(0)
  io.XmitMT := Count === 9.U
}

// UART Receiver Module
class UartRx extends Module {
  val io = IO(new Bundle {
    val ResetF = Input(Bool())
    val Serial_InT = Input(Bool())
    val DataRdyT = Output(Bool())
    val DataOuT = Output(UInt(8.W))
    val BitClkT = Output(Bool())
  })
  
  val RxInit_c = 1023.U(10.W)  // 10'b1111111111
  val RxReg = RegInit(RxInit_c)
  val Count16 = RegInit(0.U(4.W))
  val RxMT = RegInit(true.B)
  val RxIn = RegInit(false.B)
  
  // Register serial input
  RxIn := io.Serial_InT
  
  // Reset logic
  when(io.ResetF === 0.U) {
    Count16 := 0.U
    RxMT := true.B
    RxReg := RxInit_c
  }.elsewhen(RxMT === true.B && RxIn === false.B) {
    // Start bit detected
    Count16 := 0.U
    RxMT := false.B
    RxReg := RxInit_c
  }.elsewhen(Count16 === 7.U && RxMT === false.B) {
    // Mid bit clock - sample data
    RxReg := Cat(RxIn, RxReg(9,1))
    Count16 := Count16 + 1.U
  }.otherwise {
    Count16 := Count16 + 1.U
  }
  
  // Check if data word is received
  when(io.DataRdyT === true.B) {
    RxMT := true.B
  }
  
  io.DataRdyT := (RxMT === false.B) && (RxReg(9) === 1.U) && (RxReg(0) === 0.U)
  io.BitClkT := Count16 === 9.U
  io.DataOuT := RxReg(8,1)
}

// Control Module
class control extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val ld = Input(Bool())
    val dataIn = Input(UInt(8.W))
    val enable = Output(Bool())
    val parallelOut = Output(UInt(8.W))
    val parallelIn = Input(UInt(8.W))
    val shiftLoad = Output(Bool())
    val txEmpty = Input(Bool())
    val dataRdy = Input(Bool())
    val bitClock = Input(Bool())
    val ok = Output(Bool())
  })
  
  val rxBuf = RegInit(128.U(8.W))  // 8'b10000000
  val txBuf = RegInit(1.U(8.W))    // 8'b00000001
  val shiftLoadReg = RegInit(true.B)
  val freqDiv = RegInit(0.U(4.W))
  
  // Calculate enable signal based on freqDiv
  val enableWire = freqDiv === 7.U
  
  when(io.reset === 0.U) {
    shiftLoadReg := true.B
    freqDiv := 0.U
    when(io.ld === 1.U) {
      txBuf := io.dataIn
    }
  }.otherwise {
    when(io.dataRdy === 1.U) {
      rxBuf := io.parallelIn
    }
    
    when(enableWire === 1.U && io.txEmpty === 1.U) {
      when(shiftLoadReg === true.B) {
        shiftLoadReg := false.B
      }.otherwise {
        shiftLoadReg := true.B
      }
    }.elsewhen(io.ld === 1.U) {
      txBuf := io.dataIn
    }
    
    freqDiv := freqDiv + 1.U
  }
  
  io.enable := enableWire
  io.ok := rxBuf === txBuf
  io.parallelOut := txBuf
  io.shiftLoad := shiftLoadReg
}

// Buechi Module
class Buechi extends Module {
  val io = IO(new Bundle {
    val rst = Input(Bool())
    val ok = Input(Bool())
    val ld = Input(Bool())
    val fair = Output(Bool())
    val scc = Output(Bool())
  })
  
  val state = RegInit(BuechiState.Init)
  
  // Non-deterministic choices - simplified for Chisel
  // Using simple logic to approximate the non-deterministic behavior
  val ND_n1_n2 = Mux(state === BuechiState.n1, BuechiState.n2, BuechiState.n1)
  val ND_n1_n4 = Mux(state === BuechiState.n1, BuechiState.n4, BuechiState.n1)
  
  io.fair := state === BuechiState.n1
  io.scc := (state === BuechiState.n1) || (state === BuechiState.n4)
  
  switch(state) {
    is(BuechiState.n2) {
      when(Cat(io.ld, io.ok, io.rst) === "b000".U) {
        state := BuechiState.n2
      }.elsewhen(Cat(io.ld, io.ok, io.rst) === "b001".U) {
        state := ND_n1_n2
      }.elsewhen(Cat(io.ld, io.ok, io.rst)(2,1) === "b01".U) {
        state := BuechiState.n2
      }.elsewhen(io.ld === 1.U) {
        state := BuechiState.n2
      }
    }
    is(BuechiState.Trap) {
      state := BuechiState.Trap
    }
    is(BuechiState.n1) {
      when(Cat(io.ld, io.ok, io.rst) === "b000".U || Cat(io.ld, io.ok, io.rst) === "b010".U) {
        state := BuechiState.Trap
      }.elsewhen(Cat(io.ld, io.ok, io.rst) === "b001".U) {
        state := ND_n1_n4
      }.elsewhen(Cat(io.ld, io.ok, io.rst) === "b011".U) {
        state := BuechiState.n4
      }.elsewhen(io.ld === 1.U) {
        state := BuechiState.Trap
      }
    }
    is(BuechiState.n4) {
      when(Cat(io.ld, io.ok, io.rst) === "b000".U || Cat(io.ld, io.ok, io.rst) === "b010".U) {
        state := BuechiState.Trap
      }.elsewhen(Cat(io.ld, io.ok, io.rst) === "b001".U) {
        state := ND_n1_n4
      }.elsewhen(Cat(io.ld, io.ok, io.rst) === "b011".U) {
        state := BuechiState.n4
      }.elsewhen(io.ld === 1.U) {
        state := BuechiState.Trap
      }
    }
    is(BuechiState.Init) {
      state := BuechiState.n2
    }
  }
}

// Top-level nullModem Module
class nullModem extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val load = Input(Bool())
    val dataIn = Input(UInt(8.W))
    val ok = Output(Bool())
    val fair = Output(Bool())
    val scc = Output(Bool())
    // Internal signals for debugging
    val parallelOut = Output(UInt(8.W))
    val parallelIn = Output(UInt(8.W))
    val shiftLoad = Output(Bool())
    val enable = Output(Bool())
    val txEmpty = Output(Bool())
    val dataRdy = Output(Bool())
    val bitClock = Output(Bool())
    val serialOut = Output(Bool())
  })
  
  val rst = RegInit(true.B)
  val ld = RegInit(false.B)
  
  rst := io.reset
  ld := io.load
  
  // Control module instance
  val ctl = Module(new control())
  ctl.io.reset := rst
  ctl.io.ld := ld
  ctl.io.dataIn := io.dataIn
  
  // UART Transmitter instance
  val Tx = Module(new UartXmt())
  Tx.io.Shift_LdF := ctl.io.shiftLoad
  Tx.io.ClkEnbT := ctl.io.enable
  Tx.io.DataT := ctl.io.parallelOut
  Tx.io.ResetF := rst
  
  // UART Receiver instance
  val Rx = Module(new UartRx())
  Rx.io.ResetF := rst
  Rx.io.Serial_InT := Tx.io.Serial_OuT  // null modem connection
  
  // Connect control module to UART modules
  ctl.io.parallelIn := Rx.io.DataOuT
  ctl.io.txEmpty := Tx.io.XmitMT
  ctl.io.dataRdy := Rx.io.DataRdyT
  ctl.io.bitClock := Rx.io.BitClkT
  
  // Buechi module instance
  val buchi = Module(new Buechi())
  buchi.io.rst := rst
  buchi.io.ok := ctl.io.ok
  buchi.io.ld := ld
  
  // Outputs
  io.ok := ctl.io.ok
  io.fair := buchi.io.fair
  io.scc := buchi.io.scc
  
  // Debug outputs
  io.parallelOut := ctl.io.parallelOut
  io.parallelIn := Rx.io.DataOuT
  io.shiftLoad := ctl.io.shiftLoad
  io.enable := ctl.io.enable
  io.txEmpty := Tx.io.XmitMT
  io.dataRdy := Rx.io.DataRdyT
  io.bitClock := Rx.io.BitClkT
  io.serialOut := Tx.io.Serial_OuT
}

// Main object for Verilog generation
object VerilogGenerator extends App {
  emitVerilog(new nullModem(), args)
}