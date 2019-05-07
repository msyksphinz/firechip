package freechips.rocketchip.tile

import Chisel._

import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.InOrderArbiter


class WithRoccMatrixMul extends Config((site, here, up) => {
  case BuildRoCC => List(
    (p: Parameters) => {
        val accumulator = LazyModule(new AccumulatorExample(OpcodeSet.custom0, n = 4)(p))
        accumulator
    },
    (p: Parameters) => {
        val translator = LazyModule(new TranslatorExample(OpcodeSet.custom1)(p))
        translator
    },
    (p: Parameters) => {
        val counter = LazyModule(new CharacterCountExample(OpcodeSet.custom2)(p))
        counter
    },
    (p: Parameters) => {
        val matrix_mul = LazyModule(new MatrixMul(OpcodeSet.custom3)(p))
        matrix_mul
    },
  )
})


class MatrixMul(opcodes: OpcodeSet, val n: Int = 4)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new MatrixMulModuleImp(this)
}

class MatrixMulModuleImp(outer: MatrixMul, n: Int = 4)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {
  val busy = Reg(init = {Bool(false)})

  val funct  = io.cmd.bits.inst.funct
  val setM   = (funct === 0.U)
  val setK   = (funct === 1.U)
  val doCalc = (funct === 2.U)

  val r_cmd_count  = Reg(UInt(xLen.W))
  val r_recv_count = Reg(UInt(xLen.W))

  val r_matrix_max = Reg(UInt(xLen.W))
  val r_matrix_K   = Reg(UInt(xLen.W))

  val r_resp_rd = Reg(io.resp.bits.rd)
  val r_addr   = Reg(UInt(xLen.W))
  val r_v_addr = Reg(UInt(xLen.W))
  val r_h_addr = Reg(UInt(xLen.W))

  // datapath
  val r_total = Reg(UInt(xLen.W))
  val r_h_val = Reg(UInt(xLen.W))
  val r_tag   = Reg(UInt(n.W))

  val s_idle :: s_mem_fetch :: s_recv_finish :: s_mem_recv :: Nil = Enum(Bits(), 4)
  val r_cmd_state  = Reg(UInt(width = 3), init = s_idle)
  val r_recv_state = Reg(UInt(width = 3), init = s_idle)

  when (io.cmd.valid) {
    printf("MatrixMul: Funct Request. %x\n", funct)
  }

  when (io.cmd.fire() && setM) {
    printf("MatrixMul: SetLengthM Request. %x\n", io.cmd.bits.rs1)
    r_matrix_max := io.cmd.bits.rs1
    r_recv_state := s_recv_finish
  }

  when (io.cmd.fire() && setK) {
    printf("MatrixMul: SetLengthK Request. %x\n", io.cmd.bits.rs1)
    r_matrix_K   := io.cmd.bits.rs1
    r_recv_state := s_recv_finish
  }

  when (io.cmd.fire()) {
    r_total      := 0.U
    r_resp_rd := io.cmd.bits.inst.rd
  }

  when (io.cmd.fire() && doCalc) {
    printf("MatrixMul: DoCalc Received. %x, %x\n", io.cmd.bits.rs1, io.cmd.bits.rs2)

    r_v_addr     := io.cmd.bits.rs2
    r_h_addr     := io.cmd.bits.rs1

    r_recv_count := 0.U
    r_cmd_count  := 0.U
    r_tag        := 0.U

    r_cmd_state  := s_mem_fetch
    r_recv_state := s_mem_recv
  }

  // val DATA_SIZE = 8
  val DATA_SIZE = 4

  val w_addr = Mux (r_cmd_count(0) === 0.U, r_h_addr, r_v_addr)

  io.cmd.ready := (r_cmd_state === s_idle) && (r_recv_state === s_idle)
  // command resolved if no stalls AND not issuing a load that will need a request

  val cmd_request_max = (r_matrix_max << 1.U) - 1.U

  val cmd_finished = (r_cmd_count === cmd_request_max)
  when ((r_cmd_state === s_mem_fetch) && io.mem.req.fire()) {
    printf("MatrixMul: <<s_mem_fetch_v>> IO.MEM Command Fire %x\n", w_addr)

    r_cmd_count  := Mux(cmd_finished, 0.U, r_cmd_count + 1.U)

    r_h_addr     := Mux(r_cmd_count(0), r_h_addr, r_h_addr + DATA_SIZE.U)
    r_v_addr     := Mux(r_cmd_count(0), r_v_addr + (r_matrix_K << log2Ceil(DATA_SIZE).U), r_v_addr)
    r_cmd_state  := Mux(cmd_finished, s_idle, s_mem_fetch)
  }

  when (io.mem.req.fire()) {
    r_tag        := r_tag + 1.U
  }

  // MEMORY REQUEST INTERFACE
  io.mem.req.valid := (r_cmd_state === s_mem_fetch)
  io.mem.req.bits.addr := w_addr
  io.mem.req.bits.tag  := r_tag
    io.mem.req.bits.cmd  := M_XRD // perform a load (M_XWR for stores)
  if (DATA_SIZE == 8) {
    io.mem.req.bits.typ  := MT_D  // D = 8 bytes, W = 4, H = 2, B = 1
  } else if (DATA_SIZE == 4) {
    io.mem.req.bits.typ  := MT_W  // D = 8 bytes, W = 4, H = 2, B = 1
  }
  io.mem.req.bits.data := Bits(0) // we're not performing any stores...
  io.mem.req.bits.phys := Bool(false)

  val recv_finished = (r_recv_count === cmd_request_max)
  when (r_recv_state === s_mem_recv && io.mem.resp.fire()) {
    printf("MatrixMul: <<s_mem_recv_v>> IO.MEM Received %x (r_count=%d)\n", io.mem.resp.bits.data, r_recv_count)

    r_recv_count := Mux(recv_finished, 0.U, r_recv_count + 1.U)
    r_recv_state := Mux(recv_finished, s_recv_finish, s_mem_recv)

    r_h_val      := Mux(r_recv_count(0), r_h_val, io.mem.resp.bits.data)

    r_total      := Mux(r_recv_count(0), r_total + r_h_val * io.mem.resp.bits.data & "hffffffff".U, r_total)
    when (r_recv_count(0)) {
      printf("MatrixMul: <<s_mem_recv_v>> r_total update %x\n", r_total)
    }
  }

  // control
  when (io.mem.req.fire()) {
    busy := Bool(true)
  }

  when ((r_recv_state === s_recv_finish) && io.resp.fire()) {
    r_recv_state := s_idle
    printf("MatrixMul: Finished. Answer = %x\n", r_total)
  }

  // PROC RESPONSE INTERFACE
  io.resp.valid := (r_recv_state === s_recv_finish)
  // valid response if valid command, need a response, and no stalls
  io.resp.bits.rd := r_resp_rd
  // Must respond with the appropriate tag or undefined behavior
  io.resp.bits.data := r_total
  // Semantics is to always send out prior accumulator register value

  io.busy := Bool(false)
  // Be busy when have pending memory requests or committed possibility of pending requests
  io.interrupt := Bool(false)
  // Set this true to trigger an interrupt on the processor (please refer to supervisor documentation)
}
