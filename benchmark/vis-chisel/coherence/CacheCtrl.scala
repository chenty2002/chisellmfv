package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
class CacheReqStatus extends Bundle {
  val value = UInt(2.W)
}

object CacheReqStatus {
  val noop = 0.U(2.W)
  val ok = 1.U(2.W)
  val blk_rreq = 2.U(2.W)
  val blk_excl = 3.U(2.W)
}

class BlockStatus extends Bundle {
  val value = UInt(2.W)
}

object BlockStatus {
  val INVALID = 0.U(2.W)
  val SHARED = 1.U(2.W)
  val EXCLUSIVE = 2.U(2.W)
}

class CacheStatus extends Bundle {
  val value = UInt(3.W)
}

object CacheStatus {
  val Ready = 0.U(3.W)
  val Rgrant = 1.U(3.W)
  val Wgrant = 2.U(3.W)
  val Rwait = 3.U(3.W)
  val Wwait = 4.U(3.W)
}

class CacheCtrlIO extends Bundle {
  // Inputs from processor
  val read_req = Input(Bool())
  val write_req = Input(Bool())
  val data = Input(Bool())
  val address = Input(UInt(32.W)) // Using 32 bits for address_size
  
  // Output to processor
  val acknowledge = Output(Bool())
  
  // Inputs from directory
  val write_back_req = Input(Bool())
  val inval = Input(Bool())
  val blocknum = Input(UInt(32.W))
  val blk_ok = Input(Bool())
  val blk_data = Input(Bool())
  
  // Outputs to directory
  val back_data = Output(Bool())
  val cache_req = Output(UInt(2.W))
  val blk_add = Output(UInt(32.W))
}

class CacheCtrl extends Module {
  val io = IO(new CacheCtrlIO)
  
  // State registers
  val cache_state = RegInit(CacheStatus.Ready)
  val block_state = RegInit(BlockStatus.INVALID)
  val block_add = RegInit(0.U(32.W))
  val block_val = RegInit(false.B)
  val blk_add_reg = RegInit(0.U(32.W))
  val cache_req_reg = RegInit(CacheReqStatus.noop)
  
  // Output assignments
  io.back_data := Mux(cache_req_reg === CacheReqStatus.ok, block_val, false.B)
  io.acknowledge := (cache_state === CacheStatus.Rgrant) || (cache_state === CacheStatus.Wgrant)
  io.cache_req := cache_req_reg
  io.blk_add := blk_add_reg
  
  // State machine
  when(true.B) {
    switch(cache_state) {
      is(CacheStatus.Ready) {
        when(io.inval && (block_add === io.blocknum)) {
          block_state := BlockStatus.INVALID
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.elsewhen(io.write_back_req) {
          block_state := BlockStatus.SHARED
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.elsewhen(io.read_req) {
          when((block_add =/= io.address) || (block_state === BlockStatus.INVALID)) {
            cache_req_reg := CacheReqStatus.blk_rreq
            blk_add_reg := io.address
            cache_state := CacheStatus.Rwait
            block_state := BlockStatus.INVALID
          }.otherwise {
            cache_state := CacheStatus.Rgrant
            cache_req_reg := CacheReqStatus.noop
          }
        }.elsewhen(io.write_req) {
          when((block_add =/= io.address) || (block_state =/= BlockStatus.EXCLUSIVE)) {
            cache_req_reg := CacheReqStatus.blk_excl
            blk_add_reg := io.address
            cache_state := CacheStatus.Wwait
            block_state := BlockStatus.INVALID
          }.otherwise {
            cache_state := CacheStatus.Wgrant
            cache_req_reg := CacheReqStatus.noop
          }
        }.otherwise {
          cache_req_reg := CacheReqStatus.noop
          blk_add_reg := 0.U
        }
      }
      
      is(CacheStatus.Rgrant) {
        when(io.inval && (block_add === io.blocknum)) {
          block_state := BlockStatus.INVALID
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.otherwise {
          cache_state := CacheStatus.Ready
        }
      }
      
      is(CacheStatus.Wgrant) {
        when(io.inval && (block_add === io.blocknum)) {
          block_state := BlockStatus.INVALID
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.otherwise {
          block_val := io.data
          cache_state := CacheStatus.Ready
        }
      }
      
      is(CacheStatus.Rwait) {
        when(io.inval && (block_add === io.blocknum)) {
          block_state := BlockStatus.INVALID
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.elsewhen(io.write_back_req) {
          cache_state := CacheStatus.Ready
        }.elsewhen(io.blk_ok) {
          block_val := io.blk_data
          block_add := blk_add_reg
          block_state := BlockStatus.SHARED
          cache_req_reg := CacheReqStatus.noop
          cache_state := CacheStatus.Rgrant
        }.otherwise {
          cache_state := CacheStatus.Rwait
        }
      }
      
      is(CacheStatus.Wwait) {
        when(io.inval && (block_add === io.blocknum)) {
          block_state := BlockStatus.INVALID
          cache_req_reg := CacheReqStatus.ok
          cache_state := CacheStatus.Ready
        }.elsewhen(io.write_back_req) {
          cache_state := CacheStatus.Ready
        }.elsewhen(io.blk_ok) {
          block_val := io.blk_data
          block_add := blk_add_reg
          block_state := BlockStatus.EXCLUSIVE
          cache_req_reg := CacheReqStatus.noop
          cache_state := CacheStatus.Wgrant
        }.otherwise {
          cache_state := CacheStatus.Wwait
        }
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new CacheCtrl(), args)
}