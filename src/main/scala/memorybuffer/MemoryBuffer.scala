package memorybuffer

import chisel3._
import chisel3.util.{RegEnable, log2Ceil}
import dspblocks.ShiftRegisterWithReset
import dspjunctions.ValidWithSync
import dsptools.numbers._

import scala.collection._

trait MemoryBufferParams[T <: Data] {
  val protoData: T
  val nRows: Int    // corresponds to nSupports for SVM, nFeatures for PCA
  val nColumns: Int // corresponds to nFeatures for SVM, nDimensions for PCA
}

class MemoryBufferIO[T <: Data](params: MemoryBufferParams[T]) extends Bundle {
  val in = Flipped(ValidWithSync(params.protoData))
  val out = ValidWithSync(Vec(params.nColumns,Vec(params.nRows,params.protoData)))

  override def cloneType: this.type = MemoryBufferIO(params).asInstanceOf[this.type]
}
object MemoryBufferIO {
  def apply[T <: Data](params: MemoryBufferParams[T]): MemoryBufferIO[T] =
    new MemoryBufferIO(params)
}

// stripped from Cem's FFTBuffer implementation :D
class MemoryBuffer[T <: chisel3.Data : Real](val params: MemoryBufferParams[T]) extends Module {
  require(params.nRows > 0, f"Number of rows must be greater than 0, currently ${params.nRows}")
  require(params.nColumns > 0, f"Number of columns must be greater than 0, currently ${params.nColumns}")
  val io = IO(MemoryBufferIO[T](params))

  val totalSize = params.nRows * params.nColumns
  val shift_en = Wire(Bool())
  val counter = RegInit(UInt((log2Ceil(totalSize)+1).W),0.U)

  // create the register matrix
  val regs = RegInit(Vec(totalSize, params.protoData), VecInit(List.fill(totalSize)(Ring[T].zero)))

  for(i <- 0 until totalSize) {
    when(shift_en === true.B) {
      if (i == 0) regs(i) := io.in.bits
      else regs(i) := regs(i - 1)
    } .otherwise {
      regs(i) := regs(i)
    }
  }

  when(io.in.valid === true.B) {
    shift_en := true.B
    when(counter === totalSize.asUInt() ) {
      counter := 1.U
      io.out.valid := true.B
    } .otherwise {
      counter := counter + 1
      io.out.valid := false.B
    }
  } .otherwise{
    shift_en := false.B
    io.out.valid := false.B
  }
  for(x <- 0 until params.nColumns) {
    for (y <- 0 until params.nRows) {
      io.out.bits(x)(y) := regs((x * params.nRows) + y)
    }
  }
  io.out.sync := (ShiftRegisterWithReset(io.in.sync, totalSize, false.B, shift_en) && shift_en)

}
