package llmverify
import chisel3._
import chisel3.util._

class sampleq(WIDTH: Int = 2, LENGTH: Int = 4, LOGLENGTH: Int = 2) extends Module {
  val io = IO(new Bundle {
    val inaddr = Input(UInt(WIDTH.W))
    val validin = Input(Bool())
    val readin = Input(Bool())
    val bus_gnt = Input(Bool())
    val bus_req = Output(Bool())
    val outaddr = Output(UInt(WIDTH.W))
    val validout = Output(Bool())
    val outisaread = Output(Bool())
    val readheadentry = Output(UInt(WIDTH.W))
  })

  // FIFO storage
  val readfifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  val writefifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))

  // Pointers
  val readtail = RegInit(0.U(LOGLENGTH.W))
  val readhead = RegInit(0.U(LOGLENGTH.W))
  val writehead = RegInit(0.U(LOGLENGTH.W))
  val writetail = RegInit(0.U(LOGLENGTH.W))

  // Control signals
  val matchFlag = RegInit(false.B)
  val inputmatch = RegInit(false.B)
  val outaddr = RegInit(0.U(WIDTH.W))
  val outisaread = RegInit(false.B)
  val validout = RegInit(false.B)
  val storeaddr = RegInit(0.U(LOGLENGTH.W))

  // FIFO status signals
  val readempty = (readtail === readhead)
  val writeempty = (writetail === writehead)
  val readfull = ((readtail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === readhead
  val writefull = ((writetail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === writehead

  // Output assignments
  io.readheadentry := readfifo(readhead)
  io.bus_req := !(readempty && writeempty)
  io.outaddr := outaddr
  io.validout := validout
  io.outisaread := outisaread

  // Main logic
  when(io.validin && io.readin && !readfull) {
    readfifo(readtail) := io.inaddr
    readtail := readtail + 1.U
  }.elsewhen(io.validin && !io.readin && !writefull) {
    writefifo(writetail) := io.inaddr
    writetail := writetail + 1.U
  }

  when(io.bus_gnt) {
    // Matching logic
    matchFlag := false.B
    for (i <- 0 until LENGTH) {
      val writeQueueValid = ((writehead < writetail) && (i.U >= writehead) && (i.U < writetail)) ||
                           ((writehead > writetail) && (i.U >= writehead || i.U < writetail))
      when(!readempty && (readfifo(readhead) === writefifo(i.U)) && writeQueueValid) {
        matchFlag := true.B
        storeaddr := readhead
      }
    }

    // Output logic
    when(!readempty && !matchFlag) {
      outaddr := readfifo(readhead)
      readhead := readhead + 1.U
      outisaread := true.B
      validout := true.B
    }.elsewhen(!writeempty) {
      outaddr := writefifo(writehead)
      writehead := writehead + 1.U
      outisaread := false.B
      validout := true.B
    }.otherwise {
      validout := false.B
    }
  }
}

class twoQ(WIDTH: Int = 2) extends Module {
  val io = IO(new Bundle {
    val select = Input(Bool())
    val validin = Input(UInt(2.W))
    val readin = Input(UInt(2.W))
    val inaddr0 = Input(UInt(WIDTH.W))
    val inaddr1 = Input(UInt(WIDTH.W))
    
    // Internal signals exposed for verification
    val bus_req = Output(UInt(2.W))
    val validout = Output(UInt(2.W))
    val outisread = Output(UInt(2.W))
    val outaddr0 = Output(UInt(WIDTH.W))
    val outaddr1 = Output(UInt(WIDTH.W))
    val readheadentry0 = Output(UInt(WIDTH.W))
    val readheadentry1 = Output(UInt(WIDTH.W))
    val bus_gnt = Output(UInt(2.W))
  })

  // Instantiate two sampleq modules
  val q0 = Module(new sampleq(WIDTH))
  val q1 = Module(new sampleq(WIDTH))

  // Connect q0
  q0.io.inaddr := io.inaddr0
  q0.io.validin := io.validin(0)
  q0.io.readin := io.readin(0)
  
  // Connect q1
  q1.io.inaddr := io.inaddr1
  q1.io.validin := io.validin(1)
  q1.io.readin := io.readin(1)

  // Bus grant logic
  val bus_gnt = RegInit(0.U(2.W))
  
  when(io.select && q1.io.bus_req) {
    bus_gnt := 2.U(2.W)
  }.elsewhen(!io.select && q0.io.bus_req) {
    bus_gnt := 1.U(2.W)
  }.otherwise {
    bus_gnt := 0.U(2.W)
  }

  // Connect bus grant signals
  q0.io.bus_gnt := bus_gnt(0)
  q1.io.bus_gnt := bus_gnt(1)

  // Connect outputs
  io.bus_req := Cat(q1.io.bus_req, q0.io.bus_req)
  io.validout := Cat(q1.io.validout, q0.io.validout)
  io.outisread := Cat(q1.io.outisaread, q0.io.outisaread)
  io.outaddr0 := q0.io.outaddr
  io.outaddr1 := q1.io.outaddr
  io.readheadentry0 := q0.io.readheadentry
  io.readheadentry1 := q1.io.readheadentry
  io.bus_gnt := bus_gnt
}

object VerilogGenerator extends App {
  emitVerilog(new twoQ(), args)
}