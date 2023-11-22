package core

import chisel3._
import chisel3.util._

class DispatchUnit extends Module 
  with InstructionConstants {
  val io = IO(new Bundle {
	val in = Vec(FrontendConfig.decoderNum, new PipelineInstruction)
	val done = Output(Bool())

	// 整数流水线，每条流水线一个入队端口
	val ints = Vec(BackendConfig.intPipelineNum, Decoupled(new IntInstruction))
	// 访存流水线，不支持乱序访存，一条流水线多个入队端口
	val mem = Vec(FrontendConfig.decoderNum, Decoupled(new MemoryInstruction))
  })

  val intAllocBegin = RegInit(0.U(log2Ceil(BackendConfig.intPipelineNum).W))

  val isInt = VecInit(io.in.map(x => x.valid && x.iqtType === IQT_INT)).asUInt
  val isMem = VecInit(io.in.map(x => x.valid && x.iqtType === IQT_MEM)).asUInt

  val intIssue = Wire(Vec(BackendConfig.intPipelineNum, Bool()))
  val memIssue = Wire(Vec(FrontendConfig.decoderNum, Bool()))

  val intSucc = (intIssue.asUInt & VecInit(io.ints.map(_.ready)).asUInt) === intIssue.asUInt
  val memSucc = (memIssue.asUInt & VecInit(io.mem.map(_.ready)).asUInt) === memIssue.asUInt

  val succ = intSucc && memSucc
  io.done := succ

  io.ints.zip(intIssue).foreach{case (x, y) => {
	x.valid := y && succ
  }}
  io.mem.zip(memIssue).foreach{case (x, y) => {
	x.valid := y && succ
  }}


  // TODO : 根据队列剩余容量的Dispatch
  var restInt = isInt
  for (i <- 0 until BackendConfig.intPipelineNum) {
	val outIdx = intAllocBegin + i.U

	val rest = restInt.orR
	val selIdx = PriorityEncoder(restInt)
	
	intIssue(outIdx) := rest
	io.ints(outIdx).bits := io.in(selIdx)
	
	restInt = restInt & (~UIntToOH(selIdx))
  }  

  var restMem = isMem
  for (i <- 0 until FrontendConfig.decoderNum) {
	val rest = restMem.orR
	val selIdx = PriorityEncoder(restMem)

	memIssue(i) := rest
	io.mem(i).bits := io.in(selIdx)

	restMem = restMem & (~UIntToOH(selIdx))
  }

  when(succ) {
	intAllocBegin := intAllocBegin + PopCount(isInt)
  }  

  require(BackendConfig.intPipelineNum >= FrontendConfig.decoderNum)

}