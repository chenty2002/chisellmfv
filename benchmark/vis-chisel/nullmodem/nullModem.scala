package llmverify

import chisel3._
import chisel3.util._

class nullModem extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())      // active low
    val load = Input(Bool())       // load data into the transmit buffer
    val dataIn = Input(UInt(8.W))  // data to be loaded
    val ok = Output(Bool())        // transfer is correct
  })

  // Internal wires
  val parallelOut = Wire(UInt(8.W))
  val parallelIn = Wire(UInt(8.W))
  val shiftLoad = Wire(Bool())
  val enable = Wire(Bool())
  val serialOut = Wire(Bool())
  val txEmpty = Wire(Bool())
  val serialIn = Wire(Bool())
  val dataRdy = Wire(Bool())
  val bitClock = Wire(Bool())

  // These two registers are for VIS, so that CTL properties do not depend on PIs
  val rst = RegNext(io.reset, init = true.B)
  val ld = RegNext(io.load, init = false.B)

  // Instantiate modules
  val ctl = Module(new control())
  ctl.io.reset := rst
  ctl.io.ld := ld
  ctl.io.dataIn := io.dataIn
  ctl.io.parallelIn := parallelIn
  ctl.io.txEmpty := txEmpty
  ctl.io.dataRdy := dataRdy
  ctl.io.bitClock := bitClock
  
  // Read outputs from control module
  enable := ctl.io.enable
  parallelOut := ctl.io.parallelOut
  shiftLoad := ctl.io.shiftLoad
  io.ok := ctl.io.ok

  val Tx = Module(new UartXmt())
  Tx.io.Shift_LdF := shiftLoad
  Tx.io.ClkEnbT := enable
  Tx.io.DataT := parallelOut
  Tx.io.ResetF := rst
  serialOut := Tx.io.Serial_OuT
  txEmpty := Tx.io.XmitMT

  val Rx = Module(new UartRx())
  Rx.io.ResetF := rst
  Rx.io.Serial_InT := serialIn
  dataRdy := Rx.io.DataRdyT
  parallelIn := Rx.io.DataOuT
  bitClock := Rx.io.BitClkT

  // Null modem connection
  serialIn := serialOut
}

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

  val rxBuf = RegInit("b10000000".U(8.W))  // arbitrary initial value
  val txBuf = RegInit("b00000001".U(8.W))  // arbitrary initial value
  val shiftLoadReg = RegInit(true.B)
  val freqDiv = RegInit(0.U(4.W))

  io.shiftLoad := shiftLoadReg
  io.parallelOut := txBuf
  io.enable := (freqDiv === 7.U)
  io.ok := (rxBuf === txBuf)

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

    when(io.enable === 1.U && io.txEmpty === 1.U) {
      when(shiftLoadReg === 1.U) {
        shiftLoadReg := 0.U
      }.otherwise {
        shiftLoadReg := 1.U
      }
    }.elsewhen(io.ld === 1.U) {
      txBuf := io.dataIn
    }

    freqDiv := freqDiv + 1.U
  }
}

class UartRx extends Module {
  val io = IO(new Bundle {
    val ResetF = Input(Bool())
    val Serial_InT = Input(Bool())
    val DataRdyT = Output(Bool())
    val DataOuT = Output(UInt(8.W))
    val BitClkT = Output(Bool())
  })

  val RxInit_c = "b1111111111".U(10.W)
  val RxReg = RegInit(RxInit_c)
  val Count16 = RegInit(0.U(4.W))
  val RxMT = RegInit(true.B)
  val RxIn = RegInit(false.B)

  // Register the serial input
  RxIn := io.Serial_InT

  // Combinational outputs
  io.DataRdyT := (RxMT === false.B) && (RxReg(9) === 1.U) && (RxReg(0) === 0.U)
  io.BitClkT := (Count16 === 9.U)
  io.DataOuT := RxReg(8, 1)

  // Sequential logic
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
    RxReg := Cat(RxIn, RxReg(9, 1))
    Count16 := Count16 + 1.U
  }.otherwise {
    // Normal counter increment
    Count16 := Count16 + 1.U
  }

  // Check if data ready and reset RxMT
  when(io.DataRdyT === true.B) {
    RxMT := true.B
  }
}

class UartXmt extends Module {
  val io = IO(new Bundle {
    val Shift_LdF = Input(Bool())
    val ClkEnbT = Input(Bool())
    val DataT = Input(UInt(8.W))
    val ResetF = Input(Bool())
    val Serial_OuT = Output(Bool())
    val XmitMT = Output(Bool())
  })

  val XmitReg = RegInit("b1111111111".U(10.W))
  val Count = RegInit(0.U(4.W))

  io.Serial_OuT := XmitReg(0)
  io.XmitMT := (Count === 9.U)

  when(io.ResetF === 0.U) {
    XmitReg := "b1111111111".U(10.W)
    Count := 9.U
  }.elsewhen(io.ClkEnbT === 1.U && io.Shift_LdF === 0.U && io.ResetF === 1.U) {
    // Load data into transmit register
    XmitReg := Cat(1.U(1.W), io.DataT, 0.U(1.W))
    Count := 0.U
  }.elsewhen(io.ClkEnbT === 1.U && io.Shift_LdF === 1.U && io.ResetF === 1.U) {
    // Shift data out
    XmitReg := Cat(1.U(1.W), XmitReg(9, 1))
    when(Count =/= 9.U) {
      Count := Count + 1.U
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new nullModem(), args)
}