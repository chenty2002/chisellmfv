package llmverify

import chisel3._
import chisel3.util._

class bcuvis32 extends Module {
  val io = IO(new Bundle {
    // Clock and reset (clock is implicit in Chisel)
    val iResetn = Input(Bool())
    
    // I-cache interface
    val iIcache_address = Input(UInt(32.W))
    val iIcache_request_n = Input(Bool())
    val Icache_done_n_reg = Output(Bool())
    
    // D-cache interface
    val iDcache_address = Input(UInt(32.W))
    val iDcache_request_n = Input(Bool())
    val Dcache_done_n_reg = Output(Bool())
    
    // Write interface
    val iwrite_size = Input(Bool()) // 0=>32bit, 1=>64bit
    val iwrite_data = Input(UInt(32.W))
    val iwrite_request_n = Input(Bool())
    val write_accept_n_reg = Output(Bool())
    
    // Memory interface
    val imem_busin = Input(UInt(32.W))
    val imem_ack = Input(Bool())
    val imem_enable_n = Input(Bool())
    val imem_data_read_ack = Input(Bool())
    
    val mem_address_reg = Output(UInt(32.W))
    val mem_busout_reg = Output(UInt(32.W))
    val mem_control_reg = Output(UInt(3.W))
    val mem_valid_reg = Output(UInt(3.W))
    
    // Shared cache data bus
    val cache_DBUS_reg = Output(UInt(32.W))
  })
  
  // State machine using ChiselEnum
  object CustomerState extends ChiselEnum {
    val none, ifu, mauRead, mauWrite = Value
  }
  
  // Internal registers
  val customer = RegInit(CustomerState.none)
  val data_in_next_cycle = RegInit(false.B)
  
  // Input latches (registered inputs)
  val Resetn = RegNext(io.iResetn, false.B)
  val Icache_address = RegNext(io.iIcache_address, 0.U)
  val Dcache_address = RegNext(io.iDcache_address, 0.U)
  val Icache_request_n = RegNext(io.iIcache_request_n, true.B)
  val Dcache_request_n = RegNext(io.iDcache_request_n, true.B)
  val write_size = RegNext(io.iwrite_size, false.B)
  val write_data = RegNext(io.iwrite_data, 0.U)
  val write_request_n = RegNext(io.iwrite_request_n, true.B)
  val mem_busin = RegNext(io.imem_busin, 0.U)
  val mem_ack = RegNext(io.imem_ack, false.B)
  val mem_enable_n = RegNext(io.imem_enable_n, true.B)
  val mem_data_read_ack = RegNext(io.imem_data_read_ack, false.B)
  
  // Output registers
  val cache_DBUS_reg = RegInit(0.U(32.W))
  val Icache_done_n_reg = RegInit(true.B)
  val Dcache_done_n_reg = RegInit(true.B)
  val write_accept_n_reg = RegInit(true.B)
  val mem_address_reg = RegInit(0.U(32.W))
  val mem_busout_reg = RegInit(0.U(32.W))
  val mem_control_reg = RegInit(0.U(3.W))
  val mem_valid_reg = RegInit(0.U(3.W))
  
  // Connect outputs
  io.cache_DBUS_reg := cache_DBUS_reg
  io.Icache_done_n_reg := Icache_done_n_reg
  io.Dcache_done_n_reg := Dcache_done_n_reg
  io.write_accept_n_reg := write_accept_n_reg
  io.mem_address_reg := mem_address_reg
  io.mem_busout_reg := mem_busout_reg
  io.mem_control_reg := mem_control_reg
  io.mem_valid_reg := mem_valid_reg
  
  // State machine logic
  when(Resetn) {
    // Default deassert write accept if not in write state
    when(customer =/= CustomerState.mauWrite) {
      write_accept_n_reg := true.B
    }
    
    switch(customer) {
      is(CustomerState.none) {
        // Look for customers when idle
        when(!Icache_request_n) {
          customer := CustomerState.ifu
          mem_address_reg := Icache_address & "hFFFFFFF0".U(32.W)
        }.elsewhen(!Dcache_request_n) {
          customer := CustomerState.mauRead
          mem_address_reg := Dcache_address & "hFFFFFFF0".U(32.W)
        }.elsewhen(!write_request_n) {
          customer := CustomerState.mauWrite
          mem_address_reg := Dcache_address
          data_in_next_cycle := false.B
        }
      }
      
      is(CustomerState.ifu) {
        when(mem_data_read_ack === true.B) {
          cache_DBUS_reg := mem_busin
          Icache_done_n_reg := false.B
        }.elsewhen(mem_ack === false.B) {
          customer := CustomerState.none
          mem_control_reg := 0.U
          Icache_done_n_reg := true.B
        }.otherwise {
          mem_control_reg := 1.U // read block
        }
      }
      
      is(CustomerState.mauRead) {
        when(mem_data_read_ack === true.B) {
          cache_DBUS_reg := mem_busin
          Dcache_done_n_reg := false.B
        }.elsewhen(mem_ack === false.B) {
          customer := CustomerState.none
          mem_control_reg := 0.U
          Dcache_done_n_reg := true.B
        }.otherwise {
          mem_control_reg := 1.U // read block
        }
      }
      
      is(CustomerState.mauWrite) {
        when(mem_enable_n) {
          data_in_next_cycle := true.B
        }.elsewhen(data_in_next_cycle) {
          data_in_next_cycle := false.B
          mem_busout_reg := cache_DBUS_reg
        }.elsewhen(mem_ack === false.B) {
          customer := CustomerState.none
          mem_control_reg := 0.U
          write_accept_n_reg := false.B
        }.otherwise {
          write_accept_n_reg := true.B
          mem_control_reg := 2.U // write block
          
          // control_lanes logic
          when(write_size === false.B) { // 32 bit
            switch(mem_address_reg(1, 0)) {
              is("b11".U) {
                mem_valid_reg := 1.U
                mem_busout_reg := (mem_busout_reg & "hFFFFFF00".U(32.W)) | (write_data & "hFF".U(32.W))
              }
              is("b10".U) {
                mem_valid_reg := 2.U
              }
              is("b01".U) {
                mem_valid_reg := 3.U
              }
              is("b00".U) {
                mem_valid_reg := 4.U
              }
            }
          }.otherwise { // 64 bit
            switch(mem_address_reg(3, 0)) {
              is("b10".U) {
                mem_valid_reg := 5.U
              }
              is("b00".U) {
                mem_valid_reg := 6.U
              }
            }
          }
        }
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new bcuvis32(), args)
}