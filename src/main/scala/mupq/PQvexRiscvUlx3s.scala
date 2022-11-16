
package mupq

import java.io.{File, FileInputStream, FileOutputStream, IOException, OutputStream}

import scopt.OptionParser

import spinal.sim._
import spinal.core._
import spinal.lib._
import spinal.core.sim._

import spinal.lib.bus.simple._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.io.TriStateArray
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.uart.Uart
import spinal.lib.com.jtag.sim.JtagTcp

import vexriscv.VexRiscv
import vexriscv.plugin.Plugin

case class PipelinedMemoryBusRamUlx3s(size : BigInt, initialContent: File = null) extends Component{
  require(size % 4 == 0, "Size must be multiple of 4 bytes")
  require(size > 0, "Size must be greater than zero")
  val busConfig = PipelinedMemoryBusConfig(log2Up(size), 32)
  val io = new Bundle{
    val bus = slave(PipelinedMemoryBus(busConfig))
  }

  val ram = Mem(Bits(32 bits), size / 4)
  io.bus.rsp.valid := RegNext(io.bus.cmd.fire && !io.bus.cmd.write) init(False)
  io.bus.rsp.data := ram.readWriteSync(
    address = (io.bus.cmd.address >> 2).resized,
    data  = io.bus.cmd.data,
    enable  = io.bus.cmd.valid,
    write  = io.bus.cmd.write,
    mask  = io.bus.cmd.mask
  )
  io.bus.cmd.ready := True

  if (initialContent != null) {
    val input       = new FileInputStream(initialContent)
    val initContent = Array.fill[BigInt](ram.wordCount)(0)
    val fileContent = Array.ofDim[Byte](Seq(input.available, initContent.length * 4).min)
    input.read(fileContent)
    for ((byte, addr) <- fileContent.zipWithIndex) {
      val l = java.lang.Byte.toUnsignedLong(byte) << ((addr & 3) * 8)
      initContent(addr >> 2) |= BigInt(l)
    }
    ram.initBigInt(initContent)
  }

}

class PQVexRiscvUlx3s(
  val ramBlockSizes: Seq[BigInt] = Seq[BigInt](256 KiB, 128 KiB),
  val initialContent: File = null,
  val clkFrequency: HertzNumber = 25 MHz,
  val coreFrequency: HertzNumber = 25 MHz,
  cpuPlugins: () => Seq[Plugin[VexRiscv]] = PQVexRiscv.withDSPMultiplier()
)
extends PQVexRiscv(
  cpuPlugins = cpuPlugins,
  ibusRange = SizeMapping(0x80000000L, ramBlockSizes.reduce(_ + _))
) {
  val io = new Bundle {
    val RST = in Bool()
    val CLK = in Bool()
    /* UART */
    val TXD = out Bool() // TXD
    val RXD = in Bool()  // RXD
    /* JTAG */
    val TDO = out Bool() // TDO
    val TCK = in Bool()  // TCK
    val TDI = in Bool()  // TDI
    val TMS = in Bool()  // TMS
    val GPIO = in Bits(1 bits)
  }
  noIoPrefix()

  asyncReset := io.RST
  mainClock := io.CLK

  io.TDO := jtag.tdo
  jtag.tck := io.TCK
  jtag.tdi := io.TDI
  jtag.tms := io.TMS

  uart.rxd := io.RXD
  io.TXD := uart.txd

  gpio.read := io.GPIO

  val memory = new ClockingArea(systemClockDomain) {
    val ramBlocks       = ramBlockSizes.zipWithIndex.map(t => PipelinedMemoryBusRamUlx3s(t._1, if (t._2 == 0) initialContent else null))
    var curAddr: BigInt = 0x80000000L
    for (block <- ramBlocks) {
      busSlaves += block.io.bus -> SizeMapping(curAddr, block.size)
      curAddr += block.size
    }
  }
}

object PQVexRiscvUlx3s {
  def main(args: Array[String]): Unit = {
    case class PQVexRiscvUlx3sConfig(
      ramBlocks: Seq[BigInt] = Seq(256 KiB, 128 KiB),
      initFile: File = null,
      clkFrequency: HertzNumber = 25 MHz,
      coreFrequency: HertzNumber = 25 MHz,
      cpuPlugins: () => Seq[Plugin[VexRiscv]] = PQVexRiscv.baseConfig()
    )
    val optParser = new OptionParser[PQVexRiscvUlx3sConfig]("PQVexRiscvArty") {
      head("PQVexRiscvUlx3s board")
      help("help") text ("print usage text")
      opt[Seq[Int]]("ram") action ((r, c) =>
        c.copy(ramBlocks =
          r.map(_ KiB))) text ("SRAM Blocks in KiB") valueName ("<block1>,<block2>")
      opt[File]("init") action ((f, c) =>
        c.copy(initFile = f)) text ("Initialization file for first RAM block") valueName ("<bin>")
      opt[Int]("clk") action ((r, c) =>
        c.copy(clkFrequency = (r MHz))) text ("Input clock freqency in MHz") valueName ("<freq>")
      opt[Int]("core") action ((r, c) =>
        c.copy(coreFrequency = (r MHz))) text ("Target core freqency in MHz") valueName ("<freq>")
      opt[Unit]("mul") action ((_, c) =>
        c.copy(cpuPlugins = PQVexRiscv.withDSPMultiplier(c.cpuPlugins)))
    }
    val config = optParser.parse(args, PQVexRiscvUlx3sConfig()) match {
      case Some(config) => config
      case None         => ???
    }
    val report = SpinalConfig(
      mode = Verilog,
      targetDirectory = "rtl/gen"
    ).generate(
      new PQVexRiscvUlx3s(
        ramBlockSizes = config.ramBlocks,
        initialContent = config.initFile,
        clkFrequency = config.clkFrequency,
        coreFrequency = config.coreFrequency,
        cpuPlugins = config.cpuPlugins
      )
    )
    println(s"Core freqency is set to ${config.coreFrequency.toDouble / 1e6} MHz")
    report.mergeRTLSource(s"rtl/gen/${report.toplevelName}.aux")
  }
}
