// See LICENSE.SiFive for license details.

package freechips.rocketchip.subsystem

import Chisel._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

case class PeripheryBusParams(
  beatBytes: Int,
  blockBytes: Int,
  frequency: BigInt = BigInt(100000000) // 100 MHz as default bus frequency
) extends HasTLBusParams

case object PeripheryBusKey extends Field[PeripheryBusParams]

class PeripheryBus(params: PeripheryBusParams, val crossing: SubsystemClockCrossing = SynchronousCrossing())
                  (implicit p: Parameters) extends TLBusWrapper(params, "PeripheryBus")
    with HasTLXbarPhy
    with HasCrossing {

  private def bufferTo(buffer: BufferParams): TLOutwardNode =
    TLBuffer(buffer) :*= delayNode :*= outwardNode

  private def fragmentTo(minSize: Int, maxSize: Int, buffer: BufferParams): TLOutwardNode =
    TLFragmenter(minSize, maxSize) :*= bufferTo(buffer)

  private def fixedWidthTo(buffer: BufferParams): TLOutwardNode =
    TLWidthWidget(params.beatBytes) :*= bufferTo(buffer)

  def toSlave(
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLOutwardNode = {
    to(s"Slave${name.getOrElse("")}") { gen :*= bufferTo(buffer) }
  }

  def toVariableWidthSlave(
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLOutwardNode = {
    to(s"Slave${name.getOrElse("")}") {
      gen :*= fragmentTo(params.beatBytes, params.blockBytes, buffer)
    }
  }

  def toFixedWidthSlave(
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLOutwardNode = {
    to(s"Slave${name.getOrElse("")}") {
      gen :*= fixedWidthTo(buffer)
    }
  }

  def toFixedWidthSingleBeatSlave(
        widthBytes: Int,
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLOutwardNode = {
    to(s"Slave${name.getOrElse("")}") {
      gen :*= TLFragmenter(widthBytes, params.blockBytes) :*= fixedWidthTo(buffer)
    }
  }

  def toLargeBurstSlave(
        maxXferBytes: Int,
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLOutwardNode = {
    to(s"Slave${name.getOrElse("")}") {
      gen :*= fragmentTo(params.beatBytes, maxXferBytes, buffer)
    }
  }

  def fromSystemBus(
        arithmetic: Boolean = true,
        buffer: BufferParams = BufferParams.default)
      (gen: => TLOutwardNode) {
    from("SystemBus") {
      (inwardNode
        :*= TLBuffer(buffer)
        :*= TLAtomicAutomata(arithmetic = arithmetic)
        :*= gen)
    }
  }

  def fromOtherMaster(
        name: Option[String] = None,
        buffer: BufferParams = BufferParams.none)
      (gen: => TLNode): TLInwardNode = {
    from(s"OtherMaster${name.getOrElse("")}") { inwardNode :*= TLBuffer(buffer) :*= gen }
  }


  def toTile(name: Option[String] = None)(gen: => TLNode): TLOutwardNode = {
    to(s"Tile${name.getOrElse("")}") {
      FlipRendering { implicit p =>
        gen :*= delayNode :*= outwardNode
      }
    }
  }
}

/** Provides buses that serve as attachment points,
  * for use in traits that connect individual devices or external ports.
  */
trait HasPeripheryBus extends HasSystemBus {
  private val pbusParams = p(PeripheryBusKey)
  val pbusBeatBytes = pbusParams.beatBytes

  val pbus = LazyModule(new PeripheryBus(pbusParams))

  // The peripheryBus hangs off of systemBus; here we convert TL-UH -> TL-UL
  pbus.fromSystemBus() { sbus.toPeripheryBus() { pbus.crossTLIn } }
}
