package core

import chisel3._
import chisel3.util._
import svsim.Backend

class RenameTableEntry extends Bundle {
  val valid = Bool()
  val pregIdx = UInt(BackendConfig.pregIdxWidth)
}

class NewPregRequest extends Bundle {
  // 注意写x0的话这里的valid应该设成false
  val valid = Input(Bool())

  // 返回的物理寄存器下标
  val pregIdx = Output(UInt(BackendConfig.pregIdxWidth))
}

class FindPregRequest extends Bundle {
  val lregIdx = Input(UInt(5.W))

  val preg = Output(new RenameTableEntry())
}

class CommitPregRequest extends Bundle {
  val valid = Input(Bool())

  // commit时
  // 将映射关系写入到art中，让逻辑寄存器对应的上一个物理寄存器重新加入freeList里
  val lregIdx = Input(UInt(5.W))
  val pregIdx = Input(UInt(BackendConfig.pregIdxWidth))
}

class RenameRequests extends Bundle {
  // 重命名请求
  val news = Vec(FrontendConfig.decoderNum, new NewPregRequest)
  val finds = Vec(FrontendConfig.decoderNum, Vec(2, new FindPregRequest))
  // 重命名请求是否成功
  val succ = Output(Bool())
}

class RenameTable extends Module {
  val io = IO(new Bundle {
    val renames = new RenameRequests

    // 提交请求
    val commits = Vec(BackendConfig.maxCommitsNum, new CommitPregRequest)
  })

  val ctrlIO = IO(new Bundle {
    val recover = Input(Bool())
  })

  // speculative rename table
  val srt = RegInit(VecInit(Seq.fill(32)(0.U.asTypeOf(new RenameTableEntry))))
  // speculative preg free list
  // 如果为1表示已经被占用
  val sfree = RegInit(0.U(BackendConfig.physicalRegNum.W))

  // arch rename table
  val art = RegInit(VecInit(Seq.fill(32)(0.U.asTypeOf(new RenameTableEntry))))
  // arch preg free list
  val afree = RegInit(0.U(BackendConfig.physicalRegNum.W))

  val recovering = RegInit(true.B)

  when(recovering) {
    // 从art恢复出freeList与srt
    srt := art
    sfree := afree
    io.renames.news.foreach(x => {
      x.pregIdx := DontCare
    })
    io.renames.finds.foreach(x =>
      x.foreach(y => {
        y.preg := DontCare
      })
    )

    recovering := false.B
  }.otherwise {
    // 处理news和finds

    {
      // 要么全部失败，要么全部成功

      // 当前重命名表
      var curRT = Wire(Vec(32, new RenameTableEntry))
      // 当前freeList
      var curFree = Wire(UInt(BackendConfig.physicalRegNum.W))
      // 前面一个请求失败要导致后面的请求也都不改变状态
      var curFailed = Wire(Bool())

      curRT := srt
      curFree := sfree
      curFailed := false.B

      // 按指令顺序更新
      for (i <- 0 until FrontendConfig.decoderNum) {
        // 先处理Find，避免读写逻辑寄存器相同的情况
        val finds = io.renames.finds(i)
        for (j <- 0 until 2) {
          finds(j).preg := curRT(finds(j).lregIdx)
        }

        val req = io.renames.news(i)
        // 更新目前的失败状态
        curFailed = curFailed || curFree.andR
        // 更新curRT与curFree

        val select = PriorityEncoderOH(curFree)
        val selectIdx = OHToUInt(select)

        val newRT = Wire(Vec(32, new RenameTableEntry))
        newRT := curRT
        when(req.valid) {
          newRT(selectIdx).valid := true.B
          newRT(selectIdx).pregIdx := selectIdx
        }

        req.pregIdx := selectIdx

        // 注意这里是 "等于号"
        curFree = curFree & ~select
        curRT = newRT
      }

      val succ = !curFailed

      io.renames.succ := succ
      // 最终结果写回寄存器
      when(succ) {
        srt := curRT
        sfree := curFree
      }
    }

    // 处理commit

    {
      // 当前重命名表
      var curRT = Wire(Vec(32, new RenameTableEntry))
      // 当前freeList
      var curSFree = Wire(UInt(BackendConfig.physicalRegNum.W))
      var curAFree = Wire(UInt(BackendConfig.physicalRegNum.W))

      curRT := art
      curSFree := sfree
      curAFree := afree

      for (i <- 0 until BackendConfig.maxCommitsNum) {
        val req = io.commits(i)

        val lastEntry = curRT(req.lregIdx)
        val lastPregOH =
          Mux(lastEntry.valid && req.valid, UIntToOH(lastEntry.pregIdx), 0.U)
        val curPregOH = Mux(req.valid, UIntToOH(req.pregIdx), 0.U)

        // 把之前的物理寄存器重新加入SFree
        curSFree = curSFree | lastPregOH
        // 把之前的物理寄存器加入AFree，把现在的物理寄存器从AFree里删掉
        curAFree = curAFree | lastPregOH & ~curPregOH

        // 写入新的映射关系
        val newRT = Wire(Vec(32, new RenameTableEntry))
        newRT := curRT
        when(req.valid) {
          newRT(req.lregIdx).valid := true.B
          newRT(req.lregIdx).pregIdx := req.pregIdx
        }

        curRT = newRT
      }

      // 最终结果写回
      art := curRT
      sfree := curSFree
      afree := curAFree
    }

    // 回滚信号
    recovering := ctrlIO.recover
  }

}

class RenameUnit extends Module {
  val io = IO(new Bundle {
    val in = Vec(FrontendConfig.decoderNum, Input(new DecodedInstruction))
    val ready = Output(Bool())


    val out = Vec(FrontendConfig.decoderNum, Output(new PipelineInstruction))
  })

  val renameTableIO = IO(Flipped(new RenameRequests))

  val ctrlIO = IO(new Bundle {
    val flush = Input(Bool())
  })


}
