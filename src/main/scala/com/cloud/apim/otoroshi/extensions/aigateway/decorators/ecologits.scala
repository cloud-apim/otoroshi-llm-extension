package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.Types.ValueOrRange
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.math.*
import scala.util.*
import org.apache.pekko.stream.Materializer

/**
 * This code is a scala port of the [EcoLogits](https://github.com/genai-impact/ecologits) project
 * that tracks the energy consumption and environmental impacts of using generative AI models.
 *
 * This is just the compute part as we don't need client instrumentation
 *
 * Once stable we can consider to contribute it to the https://genai-impact.org/ project
 *
 */

object Types {
  type ValueOrRange = Either[Double, RangeValue]
  def valueOrRangeJson(value: ValueOrRange): JsValue = {
    value match {
      case Left(v) => RangeValue(v, v).json //JsNumber(BigDecimal(v))
      case Right(range) => range.json
    }
  }
}

// Global Warming Potential (GWP): related to climate change, commonly known as GHG emissions in kgCO2eq.
case class GWP(value: ValueOrRange) {
  def json(desc: Boolean): JsValue = {
    Json.obj("value" -> Types.valueOrRangeJson(value), "unit" -> "kgCO2eq")
      .applyOnIf(desc)(o => o ++ Json.obj("description" -> "Global Warming Potential (GWP): related to climate change, commonly known as GHG emissions in kgCO2eq"))
  }
}
// Abiotic Depletion Potential for Elements (ADPe): related to the depletion of minerals and metals in kgSbeq.
case class ADPe(value: ValueOrRange) {
  def json(desc: Boolean): JsValue = {
    Json.obj("value" -> Types.valueOrRangeJson(value), "unit" -> "kgSbeq")
      .applyOnIf(desc)(o => o ++ Json.obj("description" -> "Abiotic Depletion Potential for Elements (ADPe): related to the depletion of minerals and metals in kgSbeq"))
  }
}
// Primary Energy (PE): related to the energy consumed from primary sources like oil, gas or coal in MJ.
case class PE(value: ValueOrRange) {
  def json(desc: Boolean): JsValue = {
    Json.obj("value" -> Types.valueOrRangeJson(value), "unit" -> "MJ")
      .applyOnIf(desc)(o => o ++ Json.obj("description" -> "Primary Energy (PE): related to the energy consumed from primary sources like oil, gas or coal in MJ"))
  }
}
// Energy: related to the final electricity consumption in kWh.
// WCF: water consumed to run the request, both to cool the hardware and to produce the electricity it drew.
case class WCF(value: ValueOrRange) {
  def json(desc: Boolean): JsValue = {
    Json.obj("value" -> Types.valueOrRangeJson(value), "unit" -> "L")
      .applyOnIf(desc)(o => o ++ Json.obj("description" -> "Water Consumption Footprint"))
  }
}

case class Energy(value: ValueOrRange) {
  def json(desc: Boolean): JsValue = {
    Json.obj("value" -> Types.valueOrRangeJson(value), "unit" -> "kWh")
      .applyOnIf(desc)(o => o ++ Json.obj("description" -> "Energy: related to the final electricity consumption in kWh"))
  }
}

// Usage: related to the impacts of the energy consumption during model execution.
case class Usage(
                  energy: Energy,
                  gwp: GWP,
                  adpe: ADPe,
                  pe: PE,
                  wcf: WCF
                ) {
  def json(desc: Boolean): JsValue = Json.obj(
    "energy" -> energy.json(desc),
    "gwp" -> gwp.json(desc),
    "adpe" -> adpe.json(desc),
    "pe" -> pe.json(desc),
    "wcf" -> wcf.json(desc),
  ).applyOnIf(desc)(o => o ++ Json.obj("description" -> "related to the impacts of the energy consumption during model execution"))
}

// Embodied: related to resource extraction, manufacturing and transportation of the hardware.
case class Embodied(
                     gwp: GWP,
                     adpe: ADPe,
                     pe: PE
                   ) {
  def json(desc: Boolean): JsValue = Json.obj(
    "gwp" -> gwp.json(desc),
    "adpe" -> adpe.json(desc),
    "pe" -> pe.json(desc),
  ).applyOnIf(desc)(o => o ++ Json.obj("description" -> "related to resource extraction, manufacturing and transportation of the hardware"))
}

case class Impacts(
                    energy: Energy,
                    gwp: GWP,
                    adpe: ADPe,
                    pe: PE,
                    wcf: WCF,
                    usage: Usage,
                    embodied: Embodied
                  ) {
  def json(desc: Boolean): JsValue = Json.obj(
    "energy" -> energy.json(desc),
    "gwp" -> gwp.json(desc),
    "adpe" -> adpe.json(desc),
    "pe" -> pe.json(desc),
    // water has no embodied counterpart upstream, so the total is the usage one
    "wcf" -> wcf.json(desc),
    "usage" -> usage.json(desc),
    "embodied" -> embodied.json(desc)
  )
}


case class RangeValue(min: Double, max: Double) {
  def *(factor: Double): RangeValue = RangeValue(min * factor, max * factor)
  def *(other: RangeValue): RangeValue = RangeValue(min * other.min, max * other.max)
  def /(divisor: Double): RangeValue = RangeValue(min / divisor, max / divisor)
  def <(value: Double): Boolean = max < value
  def json: JsValue = Json.obj(
    "min" -> min,
    "max" -> max,
    "avg" -> ((min + max) / 2)
  )
}


object ValueOrRange {
  def apply(d: Double): ValueOrRange = Left(d)
  def apply(r: RangeValue): ValueOrRange = Right(r)
  def from(value: Any): ValueOrRange = value match {
    case d: Double => Left(d)
    case r: RangeValue => Right(r)
    case _ => throw new IllegalArgumentException("Unsupported type")
  }

  def *(a: ValueOrRange, b: Double): ValueOrRange = a match {
    case Left(v)     => Left(v * b)
    case Right(rv)   => Right(rv * b)
  }

  def *(a: ValueOrRange, b: ValueOrRange): ValueOrRange = (a, b) match {
    case (Left(x), Left(y))     => Left(x * y)
    case (Right(x), Right(y))   => Right(x * y)
    case (Left(x), Right(y))    => Right(y * x)
    case (Right(x), Left(y))    => Right(x * y)
  }

  def plus(a: ValueOrRange, b: ValueOrRange): ValueOrRange = (a, b) match {
    case (Left(x), Left(y)) => Left(x + y)
    case (Left(x), Right(r)) => Right(RangeValue(x + r.min, x + r.max))
    case (Right(r), Left(y)) => Right(RangeValue(r.min + y, r.max + y))
    case (Right(r1), Right(r2)) => Right(RangeValue(r1.min + r2.min, r1.max + r2.max))
  }

  def /(a: ValueOrRange, b: Double): ValueOrRange = a match {
    case Left(x)   => Left(x / b)
    case Right(x)  => Right(x / b)
  }
}

object Constants {

  // Values below mirror ecologits upstream (ecologits/impacts/llm.py). They are not tuning knobs: changing one
  // without changing the matching data makes the published impacts wrong in a way nobody will notice.
  val ModelQuantizationBits = 16

  // per-token GPU energy fit: alpha * exp(beta * batch) * active_params + gamma
  val GpuEnergyAlpha = 1.1665273170451914e-06
  val GpuEnergyBeta = -0.011205921025579175
  val GpuEnergyGamma = 4.052928146734005e-05

  // per-token latency fit, used only for models that ship no measured tps
  val GpuLatencyAlpha = 0.0006785088094353663
  val GpuLatencyBeta = 0.0003119310311688259
  val GpuLatencyGamma = 0.019473717579473387

  val GpuMemory = 80.0 // GB
  val GpuEmbodiedGWP = 273.0
  val GpuEmbodiedADPe = 0.00895
  val GpuEmbodiedPE = 3721.0

  val ServerGPUs = 8
  val ServerPower = 1.2 // kW
  val ServerEmbodiedGWP = 5700.0
  val ServerEmbodiedADPe = 0.37
  val ServerEmbodiedPE = 70000.0

  val HardwareLifespan = 3 * 365 * 24 * 60 * 60 // seconds

  // requests handled concurrently by a server: the energy and the embodied share of one request are divided
  // by it, so it moves every number
  val BatchSize = 64.0

  // fallback datacenter characteristics, used for providers we have no measurement for
  val DatacenterPUE: ValueOrRange = Left(1.2)
  val DatacenterWUE: ValueOrRange = Left(0.569)
}

/**
 * Datacenter characteristics per provider, mirroring PROVIDER_CONFIG_MAP upstream. PUE weighs the whole energy
 * bill, WUE the water one, and both differ enough between providers to matter.
 */
object DatacenterConfig {

  case class Config(location: String, pue: ValueOrRange, wue: ValueOrRange)

  private def range(min: Double, max: Double): ValueOrRange = Right(RangeValue(min, max))

  val byProvider: Map[String, Config] = Map(
    "anthropic" -> Config("USA", range(1.09, 1.14), range(0.13, 0.999)),
    "cohere" -> Config("USA", Left(1.09), Left(0.999)),
    "google" -> Config("USA", Left(1.09), Left(0.999)),
    "gemini" -> Config("USA", Left(1.09), Left(0.999)),
    "huggingface" -> Config("USA", range(1.09, 1.14), range(0.13, 0.99)),
    "mistral" -> Config("SWE", Left(1.16), Left(0.09)),
    "openai" -> Config("USA", Left(1.20), Left(0.569)),
  )

  def forProvider(provider: String): Config = {
    byProvider.getOrElse(provider.toLowerCase, Config("WOR", Constants.DatacenterPUE, Constants.DatacenterWUE))
  }
}

object LLMImpactModel {

  /** energy of a single GPU for the whole generation, in kWh. The batch size damps the per-token cost. */
  def gpuEnergy(
                 modelActiveParams: Double,
                 outputTokens: Double,
                 batchSize: Double,
                 alpha: Double,
                 beta: Double,
                 gamma: Double
               ): Double = {
    val perTokenWh = alpha * math.exp(beta * batchSize) * modelActiveParams + gamma
    outputTokens * (perTokenWh / 1000.0)
  }

  /**
   * Generation latency in seconds. When the model ships measured deployment characteristics they are used as
   * is - a measurement beats a fit - and the fitted formula only serves the models that have none. Capped by
   * the latency actually observed, since the model cannot have taken longer than the request itself.
   */
  def generationLatency(
                         modelActiveParams: Double,
                         outputTokens: Double,
                         batchSize: Double,
                         alpha: Double,
                         beta: Double,
                         gamma: Double,
                         requestLatency: Double,
                         tps: Option[Double],
                         ttft: Option[Double]
                       ): Double = {
    val latencyPerToken = tps.filter(_ > 0.0).map(1.0 / _).getOrElse(alpha * modelActiveParams + beta * batchSize + gamma)
    val gpuLatency = outputTokens * latencyPerToken + ttft.getOrElse(0.0)
    if (requestLatency < gpuLatency) requestLatency else gpuLatency
  }

  def modelRequiredMemory(
                           modelTotalParams: Double,
                           quantBits: Int
                         ): Double = {
    1.2 * modelTotalParams * quantBits / 8.0
  }

  /** rounded up to the next power of two, as a server is not filled one gpu at a time */
  def gpuRequiredCount(
                        modelRequiredMemory: Double,
                        gpuMemory: Double
                      ): Int = {
    val needed = ceil(modelRequiredMemory / gpuMemory).toInt
    if (needed <= 1) 1 else math.pow(2, ceil(log(needed.toDouble) / log(2.0))).toInt
  }

  /** energy of the server itself, for this request's share of the batch */
  def serverEnergy(
                    generationLatency: Double,
                    serverPower: Double,
                    serverGPUCount: Int,
                    gpuRequiredCount: Int,
                    batchSize: Double
                  ): Double = {
    (generationLatency / 3600.0) * serverPower * (gpuRequiredCount.toDouble / serverGPUCount.toDouble) * (1.0 / batchSize)
  }

  /** energy drawn by the IT equipment alone, before the datacenter overhead */
  def requestItEnergy(
                       serverEnergy: Double,
                       gpuRequiredCount: Int,
                       gpuEnergy: Double
                     ): Double = serverEnergy + gpuRequiredCount * gpuEnergy

  def requestEnergy(pue: ValueOrRange, requestItEnergy: Double): ValueOrRange =
    ValueOrRange * (pue, requestItEnergy)

  /**
   * Water consumption: the datacenter cools the IT equipment directly, and the electricity it draws was itself
   * produced with water. Hence the datacenter wue plus the grid wue weighted by the pue.
   */
  def requestUsageWcf(
                       requestItEnergy: Double,
                       ifElectricityMixWue: Double,
                       datacenterWue: ValueOrRange,
                       pue: ValueOrRange
                     ): ValueOrRange = {
    val gridPart = ValueOrRange * (pue, ifElectricityMixWue)
    ValueOrRange * (ValueOrRange.plus(datacenterWue, gridPart), requestItEnergy)
  }

  def usageImpact(value: ValueOrRange, impactFactor: Double): ValueOrRange =
    ValueOrRange * (value, impactFactor)

  /** the request only bears its share of the hardware, for the time it actually held it */
  def embodiedImpact(
                      embodied: Double,
                      lifetime: Double,
                      latency: Double,
                      batchSize: Double
                    ): Double = latency * embodied / (lifetime * batchSize)

  def combinedEmbodiedImpact(
                              serverEmbodied: Double,
                              serverGPUCount: Double,
                              gpuEmbodied: Double,
                              gpuRequiredCount: Int
                            ): Double = {
    (gpuRequiredCount / serverGPUCount) * serverEmbodied + gpuRequiredCount * gpuEmbodied
  }
}

object LLMImpactExecutor {

  /** one run of the impact graph, for a single point of every input that could be a range */
  def computeLLMImpactsDag(
                            modelActiveParams: Double,
                            modelTotalParams: Double,
                            outputTokens: Double,
                            requestLatency: Double,
                            ifElectricityMixAdpe: Double,
                            ifElectricityMixPe: Double,
                            ifElectricityMixGwp: Double,
                            ifElectricityMixWue: Double,
                            datacenterPue: ValueOrRange,
                            datacenterWue: ValueOrRange,
                            tps: Option[Double] = None,
                            ttft: Option[Double] = None,
                            modelQuantBits: Int = Constants.ModelQuantizationBits,
                            batchSize: Double = Constants.BatchSize,
                            gpuEnergyAlpha: Double = Constants.GpuEnergyAlpha,
                            gpuEnergyBeta: Double = Constants.GpuEnergyBeta,
                            gpuEnergyGamma: Double = Constants.GpuEnergyGamma,
                            gpuLatencyAlpha: Double = Constants.GpuLatencyAlpha,
                            gpuLatencyBeta: Double = Constants.GpuLatencyBeta,
                            gpuLatencyGamma: Double = Constants.GpuLatencyGamma,
                            gpuMemory: Double = Constants.GpuMemory,
                            gpuEmbodiedGwp: Double = Constants.GpuEmbodiedGWP,
                            gpuEmbodiedAdpe: Double = Constants.GpuEmbodiedADPe,
                            gpuEmbodiedPe: Double = Constants.GpuEmbodiedPE,
                            serverGpuCount: Int = Constants.ServerGPUs,
                            serverPower: Double = Constants.ServerPower,
                            serverEmbodiedGwp: Double = Constants.ServerEmbodiedGWP,
                            serverEmbodiedAdpe: Double = Constants.ServerEmbodiedADPe,
                            serverEmbodiedPe: Double = Constants.ServerEmbodiedPE,
                            serverLifetime: Double = Constants.HardwareLifespan
                          ): Map[String, ValueOrRange] = {

    val gpuEnergy = LLMImpactModel.gpuEnergy(modelActiveParams, outputTokens, batchSize, gpuEnergyAlpha, gpuEnergyBeta, gpuEnergyGamma)
    val latency = LLMImpactModel.generationLatency(modelActiveParams, outputTokens, batchSize, gpuLatencyAlpha, gpuLatencyBeta, gpuLatencyGamma, requestLatency, tps, ttft)
    val memoryRequired = LLMImpactModel.modelRequiredMemory(modelTotalParams, modelQuantBits)
    val gpuRequired = LLMImpactModel.gpuRequiredCount(memoryRequired, gpuMemory)
    val serverEnergy = LLMImpactModel.serverEnergy(latency, serverPower, serverGpuCount, gpuRequired, batchSize)
    val itEnergy = LLMImpactModel.requestItEnergy(serverEnergy, gpuRequired, gpuEnergy)
    val requestEnergy = LLMImpactModel.requestEnergy(datacenterPue, itEnergy)

    val usageGWP = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixGwp)
    val usageADPe = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixAdpe)
    val usagePE = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixPe)
    val usageWCF = LLMImpactModel.requestUsageWcf(itEnergy, ifElectricityMixWue, datacenterWue, datacenterPue)

    val embodiedGWPValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedGwp, serverGpuCount, gpuEmbodiedGwp, gpuRequired)
    val embodiedADPeValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedAdpe, serverGpuCount, gpuEmbodiedAdpe, gpuRequired)
    val embodiedPEValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedPe, serverGpuCount, gpuEmbodiedPe, gpuRequired)

    Map(
      "request_energy" -> requestEnergy,
      "request_usage_gwp" -> usageGWP,
      "request_usage_adpe" -> usageADPe,
      "request_usage_pe" -> usagePE,
      "request_usage_wcf" -> usageWCF,
      "request_embodied_gwp" -> Left(LLMImpactModel.embodiedImpact(embodiedGWPValue, serverLifetime, latency, batchSize)),
      "request_embodied_adpe" -> Left(LLMImpactModel.embodiedImpact(embodiedADPeValue, serverLifetime, latency, batchSize)),
      "request_embodied_pe" -> Left(LLMImpactModel.embodiedImpact(embodiedPEValue, serverLifetime, latency, batchSize))
    )
  }
}

object LLMImpactCalculator {

  private val rangedFields = Seq(
    "request_energy", "request_usage_gwp", "request_usage_adpe", "request_usage_pe", "request_usage_wcf",
    "request_embodied_gwp", "request_embodied_adpe", "request_embodied_pe"
  )

  private def merge(prev: ValueOrRange, current: ValueOrRange): ValueOrRange = {
    def lo(v: ValueOrRange): Double = v match { case Left(x) => x; case Right(r) => r.min }
    def hi(v: ValueOrRange): Double = v match { case Left(x) => x; case Right(r) => r.max }
    Right(RangeValue(min(lo(prev), lo(current)), max(hi(prev), hi(current))))
  }

  private def sum(a: ValueOrRange, b: ValueOrRange): ValueOrRange = ValueOrRange.plus(a, b)

  /**
   * Impacts of one generation. A model whose parameter count is a range is run at both ends and the results
   * combined, which is where the reported ranges come from - not from an assumed measurement error.
   */
  def computeLLMImpacts(
                         modelActiveParams: ValueOrRange,
                         modelTotalParams: ValueOrRange,
                         outputTokens: Double,
                         ifElectricityMixAdpe: Double,
                         ifElectricityMixPe: Double,
                         ifElectricityMixGwp: Double,
                         ifElectricityMixWue: Double,
                         datacenterPue: ValueOrRange = Constants.DatacenterPUE,
                         datacenterWue: ValueOrRange = Constants.DatacenterWUE,
                         requestLatency: Option[Double] = None,
                         tps: Option[Double] = None,
                         ttft: Option[Double] = None,
                       ): Impacts = {

    val latency = requestLatency.getOrElse(Double.PositiveInfinity)

    val (activeValues, totalValues) = (modelActiveParams, modelTotalParams) match {
      case (Left(a), Left(t)) => (Seq(a), Seq(t))
      case (Right(a), Right(t)) => (Seq(a.min, a.max), Seq(t.min, t.max))
      case (Right(a), Left(t)) => (Seq(a.min, a.max), Seq(t, t))
      case (Left(a), Right(t)) => (Seq(a, a), Seq(t.min, t.max))
    }

    val results = mutable.Map[String, ValueOrRange]()
    for ((act, tot) <- activeValues zip totalValues) {
      val res = LLMImpactExecutor.computeLLMImpactsDag(
        modelActiveParams = act,
        modelTotalParams = tot,
        outputTokens = outputTokens,
        requestLatency = latency,
        ifElectricityMixAdpe = ifElectricityMixAdpe,
        ifElectricityMixPe = ifElectricityMixPe,
        ifElectricityMixGwp = ifElectricityMixGwp,
        ifElectricityMixWue = ifElectricityMixWue,
        datacenterPue = datacenterPue,
        datacenterWue = datacenterWue,
        tps = tps,
        ttft = ttft,
      )
      rangedFields.foreach { field =>
        results(field) = results.get(field).map(prev => merge(prev, res(field))).getOrElse(res(field))
      }
    }

    val energy = Energy(results("request_energy"))
    val gwpUsage = results("request_usage_gwp")
    val adpeUsage = results("request_usage_adpe")
    val peUsage = results("request_usage_pe")
    val wcfUsage = results("request_usage_wcf")
    val gwpEmbodied = results("request_embodied_gwp")
    val adpeEmbodied = results("request_embodied_adpe")
    val peEmbodied = results("request_embodied_pe")

    Impacts(
      energy = energy,
      gwp = GWP(sum(gwpUsage, gwpEmbodied)),
      adpe = ADPe(sum(adpeUsage, adpeEmbodied)),
      pe = PE(sum(peUsage, peEmbodied)),
      // no embodied water upstream, so the total is the usage one
      wcf = WCF(wcfUsage),
      usage = Usage(
        energy = energy,
        gwp = GWP(gwpUsage),
        adpe = ADPe(adpeUsage),
        pe = PE(peUsage),
        wcf = WCF(wcfUsage),
      ),
      embodied = Embodied(
        gwp = GWP(gwpEmbodied),
        adpe = ADPe(adpeEmbodied),
        pe = PE(peEmbodied),
      )
    )
  }
}

case class ImpactsOutput(
                          energy: Option[Energy] = None,
                          gwp: Option[GWP] = None,
                          adpe: Option[ADPe] = None,
                          pe: Option[PE] = None,
                          wcf: Option[WCF] = None,
                          usage: Option[Usage] = None,
                          embodied: Option[Embodied] = None,
                          warnings: Option[List[String]] = None,
                        ) {
  def hasWarnings: Boolean = warnings.exists(_.nonEmpty)

  def addWarning(warning: String): ImpactsOutput =
    this.copy(warnings = Some(warnings.getOrElse(Nil) :+ warning))

  def json(desc: Boolean): JsValue = Json.obj(
    "usage" -> usage.map(_.json(desc)).getOrElse(JsNull).asValue,
    "embodied" -> embodied.map(_.json(desc)).getOrElse(JsNull).asValue,
    "energy" -> energy.map(_.json(desc)).getOrElse(JsNull).asValue,
    "gwp" -> gwp.map(_.json(desc)).getOrElse(JsNull).asValue,
    "adpe" -> adpe.map(_.json(desc)).getOrElse(JsNull).asValue,
    "pe" -> pe.map(_.json(desc)).getOrElse(JsNull).asValue,
    "wcf" -> wcf.map(_.json(desc)).getOrElse(JsNull).asValue,
    "warnings" -> warnings.map(v => JsArray(v.map(_.json))).getOrElse(JsNull).asValue,
  )
}


// `deployment` holds the tokens per second and time to first token measured upstream for this model. When it
// is there it replaces the fitted latency formula, which only ever was a stand-in for a measurement.
case class Deployment(tps: Option[Double], ttft: Option[Double])

object Deployment {
  val empty: Deployment = Deployment(None, None)
  def from(json: JsValue): Deployment = Deployment(
    tps = json.select("tps").asOpt[Double].filter(_ > 0.0),
    ttft = json.select("ttft").asOpt[Double].filter(_ >= 0.0),
  )
}

case class Model(
  provider: String,
  name: String,
  architecture: Architecture,
  warnings: List[String],
  sources: List[String],
  deployment: Deployment = Deployment.empty,
) {
  def hasWarnings: Boolean = warnings.nonEmpty
}
case class ParametersMoE(total: ValueOrRange, active: ValueOrRange)
// `warnings` says which of the four factors are not measured for this zone but taken from the world average.
// 165 of the 215 zones have at least one, so dropping them would publish estimates as if they were measured.
case class ElectricityMix(name: String, adpe: Double, pe: Double, gwp: Double, wue: Double, warnings: List[String] = List.empty)

object ElectricityMix {
  def fromJson(json: JsValue): Option[(String, ElectricityMix)] = json.select("name").asOptString.map { name =>
    (name, ElectricityMix(
      name = name,
      adpe = json.select("adpe").asOpt[Double].getOrElse(0.0),
      pe = json.select("pe").asOpt[Double].getOrElse(0.0),
      gwp = json.select("gwp").asOpt[Double].getOrElse(0.0),
      wue = json.select("wue").asOpt[Double].getOrElse(0.0),
      warnings = json.select("warnings").asOpt[List[String]].getOrElse(List.empty),
    ))
  }

  def fromDocument(raw: String): Map[String, ElectricityMix] = {
    raw.trim.parseJson.select("electricity_mixes").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(fromJson).toMap
  }

  /** the pre-json override format, still accepted so an existing `custom-electricity-mix` keeps working */
  def fromLegacyCsv(raw: String): Map[String, ElectricityMix] = {
    raw.split("\n").toSeq.drop(1).map(_.trim).filter(_.nonEmpty).flatMap { line =>
      val parts = line.split(",")
      if (parts.length < 4) None else Some((parts(0), ElectricityMix(
        name = parts(0), adpe = parts(1).toDouble, pe = parts(2).toDouble, gwp = parts(3).toDouble,
        wue = parts.lift(4).map(_.trim).filter(_.nonEmpty).map(_.toDouble).getOrElse(0.0),
      )))
    }.toMap
  }

  def parse(raw: String): Map[String, ElectricityMix] = {
    if (raw.trim.startsWith("{")) fromDocument(raw) else fromLegacyCsv(raw)
  }
}
case class Architecture(typ: String, denseParameters: Option[ValueOrRange], moeParameters: Option[ParametersMoE])
object Architecture {
  val default = Architecture("dense", Some(Right(RangeValue(0, 0))), None)
  def from(json: JsObject): Architecture = try {
    val typ = json.select("type").asString
    Architecture(
      typ = typ,
      denseParameters = json.select("parameters").asOpt[JsObject].filter(_ => typ == "dense").filter(o => o.select("total").isEmpty).map { o =>
        Right(RangeValue(
          o.select("min").asOptInt.map(_.toDouble).orElse(o.select("min").asOpt[Double]).getOrElse(0.0),
          o.select("max").asOptInt.map(_.toDouble).orElse(o.select("max").asOpt[Double]).getOrElse(0.0),
        ))
      }.orElse {
        json.select("parameters").asOpt[JsNumber].filter(_ => typ == "dense").map { number =>
          Left(number.value.toDouble)
        }
      },
      moeParameters = json.select("parameters").asOpt[JsObject].filter(_ => typ == "moe").filter(o => o.select("total").isDefined).map { o =>
        o.select("active").asOpt[JsValue] match {
          case Some(active @ JsObject(_)) => {
            ParametersMoE(
              Left(o.select("total").as[JsNumber].value.toDouble),
              Right(RangeValue(
                active.select("min").asOptInt.map(_.toDouble).orElse(active.select("min").asOpt[Double]).getOrElse(0.0),
                active.select("max").asOptInt.map(_.toDouble).orElse(active.select("max").asOpt[Double]).getOrElse(0.0),
              ))
            )
          }
          case Some(JsNumber(active)) => {
            ParametersMoE(
              Left(o.select("total").as[JsNumber].value.toDouble),
              Right(RangeValue(
                active.toDouble,
                active.toDouble
              ))
            )
          }
          case _ => ParametersMoE(
            Left(o.select("total").as[JsNumber].value.toDouble),
            Right(RangeValue(
              o.select("min").asOptInt.map(_.toDouble).orElse(o.select("min").asOpt[Double]).getOrElse(0.0),
              o.select("max").asOptInt.map(_.toDouble).orElse(o.select("max").asOpt[Double]).getOrElse(0.0),
            ))
          )
        }
      }
    )
  } catch {
    case e: Throwable =>
      val element = e.getStackTrace.filter(_.getClassName == "com.cloud.apim.otoroshi.extensions.aigateway.decorators.Architecture$").head
      val line = s"${element.getFileName} - line ${element.getLineNumber}"
      println(s"error parsing - ${e.getMessage} - ${line} - \n${json.prettify}")
      // e.printStackTrace()
      default
  }
}

case class LLMImpactsSettings(configuration: Configuration) {
  val electricityMixZone = configuration.getOptional[String]("electricity-mix").getOrElse("WOR")
  val embedDescriptionInJson = configuration.getOptional[Boolean]("embed-description-in-json").getOrElse(true)
  val embedImpactsInResponses = configuration.getOptional[Boolean]("embed-impacts-in-responses").getOrElse(false)
  val enabled = configuration.getOptional[Boolean]("enabled").getOrElse(true)
}

class LLMImpacts(settings: LLMImpactsSettings, env: Env) {

  val default_models_by_provider_name: Map[String, Model] = {
    val modelsJson = Json.parse(getResourceCode("data/eg-models.json"))
    val rawModels = modelsJson.select("models").as[Seq[JsObject]].map { obj =>
      val provider = obj.select("provider").asString
      val name = obj.select("name").asString
      (s"${provider}-${name}", Model(
        provider = provider,
        name = name,
        warnings = obj.select("warnings").asOpt[List[String]].getOrElse(List.empty),
        sources = obj.select("sources").asOpt[List[String]].getOrElse(List.empty),
        architecture = Architecture.from(obj.select("architecture").asObject),
        deployment = Deployment.from(obj.select("deployment").asOpt[JsObject].getOrElse(Json.obj()))
      ))
    }
    rawModels.toMap
  }

  val custom_models_by_provider_name: Map[String, Model] = {
    val modelsJson = Json.parse(getResourceCode("data/custom-eg-models.json"))
    val rawModels = modelsJson.select("models").as[Seq[JsObject]].map { obj =>
      val provider = obj.select("provider").asString
      val name = obj.select("name").asString
      (s"${provider}-${name}", Model(
        provider = provider,
        name = name,
        warnings = obj.select("warnings").asOpt[List[String]].getOrElse(List.empty),
        sources = obj.select("sources").asOpt[List[String]].getOrElse(List.empty),
        architecture = Architecture.from(obj.select("architecture").asObject),
        deployment = Deployment.from(obj.select("deployment").asOpt[JsObject].getOrElse(Json.obj()))
      ))
    }
    rawModels.toMap
  }

  val user_models_by_provider_name: Map[String, Model] = {
    val modelsJson = settings.configuration.getOptional[String]("custom-models").getOrElse("{\"aliases\": [],\"models\": []}").parseJson
    val rawModels = modelsJson.select("models").as[Seq[JsObject]].map { obj =>
      val provider = obj.select("provider").asString
      val name = obj.select("name").asString
      (s"${provider}-${name}", Model(
        provider = provider,
        name = name,
        warnings = obj.select("warnings").asOpt[List[String]].getOrElse(List.empty),
        sources = obj.select("sources").asOpt[List[String]].getOrElse(List.empty),
        architecture = Architecture.from(obj.select("architecture").asObject),
        deployment = Deployment.from(obj.select("deployment").asOpt[JsObject].getOrElse(Json.obj()))
      ))
    }
    rawModels.toMap
  }

  val models_by_provider_name: Map[String, Model] = default_models_by_provider_name ++ custom_models_by_provider_name ++ user_models_by_provider_name

  val default_electricityMixes: Map[String, ElectricityMix] = ElectricityMix.fromDocument(getResourceCode("data/eg-elec.json"))

  val custom_electricityMixes: Map[String, ElectricityMix] = ElectricityMix.fromDocument(getResourceCode("data/custom-eg-elec.json"))

  // accepts the upstream json shape, and still the old csv one so an existing configuration keeps working
  val user_electricityMixes: Map[String, ElectricityMix] =
    ElectricityMix.parse(settings.configuration.getOptional[String]("custom-electricity-mix").getOrElse("{\"electricity_mixes\": []}"))

  val electricityMixes = default_electricityMixes ++ custom_electricityMixes ++ user_electricityMixes

  def getResourceCode(path: String): String = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    given mat: Materializer = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  def canHandle(provider: String, modelName: String): Boolean = {
    models_by_provider_name.contains(s"${provider}-${modelName}")
  }

  def llmImpacts(
                  provider: String,
                  modelName: String,
                  outputTokenCount: Int,
                  requestLatency: Double,
                  electricityMixZoneOpt: Option[String],
                ): Either[String, ImpactsOutput] = {

    models_by_provider_name.get(s"${provider}-${modelName}") match {
      case None => Left(s"Could not find model `$modelName` for $provider provider.")
      case Some(model) => {
        def run(totalParams: ValueOrRange, activeParams: ValueOrRange): Either[String, ImpactsOutput] = {
          val mix = electricityMixes.getOrElse(electricityMixZoneOpt.getOrElse(settings.electricityMixZone), electricityMixes.head._2)
          // the datacenter the model actually runs in, when we know it: pue and wue differ enough between
          // providers to move the result more than most of the model characteristics do
          val datacenter = DatacenterConfig.forProvider(model.provider)
          val result = LLMImpactCalculator.computeLLMImpacts(
            modelActiveParams = activeParams,
            modelTotalParams = totalParams,
            outputTokens = outputTokenCount,
            requestLatency = Some(requestLatency),
            ifElectricityMixAdpe = mix.adpe,
            ifElectricityMixPe = mix.pe,
            ifElectricityMixGwp = mix.gwp,
            ifElectricityMixWue = mix.wue,
            datacenterPue = datacenter.pue,
            datacenterWue = datacenter.wue,
            tps = model.deployment.tps,
            ttft = model.deployment.ttft,
          )
          val output = ImpactsOutput(
            energy = Some(result.energy),
            gwp = Some(result.gwp),
            adpe = Some(result.adpe),
            pe = Some(result.pe),
            wcf = Some(result.wcf),
            usage = Some(result.usage),
            embodied = Some(result.embodied)
          )
          // what the model and the zone could not tell us: an estimated water factor is worth saying out loud
          val warned = (model.warnings ++ mix.warnings).foldLeft(output)((acc, warning) => acc.addWarning(warning))
          warned.right
        }
        model.architecture.moeParameters match {
          case Some(moe) => run(moe.total, moe.active)
          case _ => model.architecture.denseParameters match {
            case None => Left(s"Could read model denseParameters.")
            case Some(denseParams) => run(denseParams, denseParams)
          }
        }
      }
    }
  }
}


object ChatClientWithEcoImpact {
  val key = TypedKey[ImpactsOutput]("cloud-apim.ai-gateway.ImpactsOutputKey")
  val enabledRef = new AtomicReference[Option[Boolean]](None)
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (enabledRef.get().isEmpty) {
      enabledRef.set(Some(tuple._3.adminExtensions.extension[AiExtension].get.llmImpactsSettings.enabled))
    }
    if (enabledRef.get().get) {
      new ChatClientWithEcoImpact(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithEcoImpact(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  def getModel(originalBody: JsValue): String = {
    val allowConfigOverride = originalProvider.options.select("allow_config_override").asOptBoolean.getOrElse(true)
    if (allowConfigOverride) originalBody.select("model").asOptString.getOrElse(chatClient.computeModel(originalBody).getOrElse("--")) else chatClient.computeModel(originalBody).getOrElse("--")
  }

  /**
   * possible provider are:
   *
   * - mistralai
   * - google
   * - anthropic
   * - openai
   * - cohere
   * - huggingface_hub
   *
   */
  def getProvider(): Option[String] = {
    originalProvider.provider.toLowerCase() match {
      case "openai" => "openai".some
      case "scaleway" => None
      case "deepseek" => None
      case "x-ai" => None
      case "ovh-ai-endpoints" => None
      case "ovh-ai-endpoints-unified" => None
      case "azure-openai" => None
      case "azure-ai-foundry" => None
      case "cloudflare" => None
      case "gemini" => "google".some
      case "huggingface" => "huggingface_hub".some
      case "mistral" => "mistralai".some
      case "ollama" => None
      case "cohere" => "cohere".some
      case "anthropic" => "anthropic".some
      case "groq" => None
      case _ => None
    }
  }

  private def handleStream(attrs: TypedMap, originalBody: JsValue)(f: => Future[Either[JsValue, Source[ChatResponseChunk, ?]]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    getProvider() match {
      case None => f // unsupported provider
      case Some(provider) => {
        val start = System.currentTimeMillis()
        f.map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val promise = Promise.apply[Option[ChatResponseChunk]]()
            val ext = env.adminExtensions.extension[AiExtension].get
            val finalProvider = originalProvider.metadata.getOrElse("eco-impacts-provider", provider)
            val modelName = originalProvider.metadata.getOrElse("eco-impacts-model", getModel(originalBody))
            val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_impacts")).contains("true")
            val addCostsInResp = ext.llmImpactsSettings.embedImpactsInResponses || enableInRequest
            if (ext.llmImpacts.canHandle(finalProvider, modelName)) {
              (resp: Source[ChatResponseChunk, Any]).applyOnIf(addCostsInResp) { src =>
                src.map(r => r.copy(choices = r.choices.map(c => c.copy(finishReason = None))))
              }.alsoTo(Sink.onComplete { _ =>
                val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
                val generationTokens = usageSlug.select("usage").select("generation_tokens").asOptLong.getOrElse(-1L)
                val reasoningTokens = usageSlug.select("usage").select("reasoning_tokens").asOptLong.getOrElse(-1L)
                val ext = env.adminExtensions.extension[AiExtension].get
                ext.llmImpacts.llmImpacts(
                  provider = finalProvider,
                  modelName = modelName,
                  outputTokenCount = (generationTokens + reasoningTokens).toInt,
                  requestLatency = (System.currentTimeMillis() - start).toDouble,
                  electricityMixZoneOpt = originalProvider.metadata.get("eco-impacts-electricity-mix-zone"),
                ) match {
                  case Left(_) => promise.trySuccess(None)
                  case Right(impacts) if !addCostsInResp =>
                    attrs.put(ChatClientWithEcoImpact.key -> impacts)
                    promise.trySuccess(None)
                  case Right(impacts) =>
                    attrs.put(ChatClientWithEcoImpact.key -> impacts)
                    promise.trySuccess(ChatResponseChunk(
                      id = s"chatcmpl-${ULID.random().toLowerCase()}",
                      created = (System.currentTimeMillis() / 1000L),
                      model = modelName,
                      choices = Seq(ChatResponseChunkChoice(
                        index = 0L,
                        delta = ChatResponseChunkChoiceDelta(None),
                        finishReason = "stop".some,
                      )),
                      impacts = impacts.some
                    ).some)
                }
              }).concat(Source.lazyFuture(() => promise.future).flatMapConcat(opt => Source(opt.toList))).right
            } else {
              resp.right
            }

          }
        }
      }
    }
  }

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    getProvider() match {
      case None => chatClient.invoke(kind, prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        val start = System.currentTimeMillis()
        chatClient.invoke(kind, prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            ext.llmImpacts.llmImpacts(
              provider = originalProvider.metadata.getOrElse("eco-impacts-provider", provider),
              modelName = originalProvider.metadata.getOrElse("eco-impacts-model", getModel(originalBody)),
              outputTokenCount = (usage.generationTokens + usage.reasoningTokens).toInt,
              requestLatency = (System.currentTimeMillis() - start).toDouble,
              electricityMixZoneOpt = originalProvider.metadata.get("eco-impacts-electricity-mix-zone"),
            ) match {
              case Left(_) => Right(resp)
              case Right(impacts) => {
                attrs.put(ChatClientWithEcoImpact.key -> impacts)
                // impacts.json(ext.llmImpactsSettings.embedDescriptionInJson).prettify.debugPrintln
                val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_impacts")).contains("true")
                if (ext.llmImpactsSettings.embedImpactsInResponses || enableInRequest) {
                  Right(resp.copy(metadata = resp.metadata.copy(impacts = impacts.some)))
                } else {
                  Right(resp)
                }
              }
            }
          }
        }
      }
    }
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    handleStream(attrs, originalBody) {
      chatClient.invokeStream(kind, prompt, attrs, originalBody)
    }
  }
}

