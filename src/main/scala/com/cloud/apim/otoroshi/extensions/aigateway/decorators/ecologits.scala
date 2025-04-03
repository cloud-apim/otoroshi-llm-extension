package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.Types.ValueOrRange
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.Configuration
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.math._
import scala.util._

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
                  pe: PE
                ) {
  def json(desc: Boolean): JsValue = Json.obj(
    "energy" -> energy.json(desc),
    "gwp" -> gwp.json(desc),
    "adpe" -> adpe.json(desc),
    "pe" -> pe.json(desc),
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
                    usage: Usage,
                    embodied: Embodied
                  ) {
  def json(desc: Boolean): JsValue = Json.obj(
    "energy" -> energy.json(desc),
    "gwp" -> gwp.json(desc),
    "adpe" -> adpe.json(desc),
    "pe" -> pe.json(desc),
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

  def /(a: ValueOrRange, b: Double): ValueOrRange = a match {
    case Left(x)   => Left(x / b)
    case Right(x)  => Right(x / b)
  }
}

object Constants {
  val ModelQuantizationBits = 4

  val GpuEnergyAlpha = 8.91e-8
  val GpuEnergyBeta = 1.43e-6
  val GpuEnergyStdev = 5.19e-7
  val GpuLatencyAlpha = 8.02e-4
  val GpuLatencyBeta = 2.23e-2
  val GpuLatencyStdev = 7.00e-6

  val GpuMemory = 80.0 // GB
  val GpuEmbodiedGWP = 143.0
  val GpuEmbodiedADPe = 5.1e-3
  val GpuEmbodiedPE = 1828.0

  val ServerGPUs = 8
  val ServerPower = 1.0 // kW
  val ServerEmbodiedGWP = 3000.0
  val ServerEmbodiedADPe = 0.24
  val ServerEmbodiedPE = 38000.0

  val HardwareLifespan = 5 * 365 * 24 * 60 * 60 // en secondes

  val DatacenterPUE = 1.2
}

object LLMImpactModel {

  def gpuEnergy(
                 modelActiveParams: Double,
                 outputTokens: Double,
                 alpha: Double,
                 beta: Double,
                 stdev: Double
               ): RangeValue = {
    val mean = alpha * modelActiveParams + beta
    val minEnergy = outputTokens * (mean - 1.96 * stdev)
    val maxEnergy = outputTokens * (mean + 1.96 * stdev)
    RangeValue(min = max(0, minEnergy), max = maxEnergy)
  }

  def generationLatency(
                         modelActiveParams: Double,
                         outputTokens: Double,
                         alpha: Double,
                         beta: Double,
                         stdev: Double,
                         requestLatency: Double
                       ): ValueOrRange = {
    val mean = alpha * modelActiveParams + beta
    val minLat = outputTokens * (mean - 1.96 * stdev)
    val maxLat = outputTokens * (mean + 1.96 * stdev)
    val interval = RangeValue(max(0, minLat), maxLat)
    if (interval < requestLatency) Right(interval)
    else Left(requestLatency)
  }

  def modelRequiredMemory(
                           modelTotalParams: Double,
                           quantBits: Int
                         ): Double = {
    1.2 * modelTotalParams * quantBits / 8.0
  }

  def gpuRequiredCount(
                        modelRequiredMemory: Double,
                        gpuMemory: Double
                      ): Int = ceil(modelRequiredMemory / gpuMemory).toInt

  def serverEnergy(
                    generationLatency: Double,
                    serverPower: Double,
                    serverGPUCount: Int,
                    gpuRequiredCount: Int
                  ): Double = {
    (generationLatency / 3600.0) * serverPower * (gpuRequiredCount.toDouble / serverGPUCount.toDouble)
  }

  def requestEnergy(
                     pue: Double,
                     serverEnergy: Double,
                     gpuRequiredCount: Int,
                     gpuEnergy: ValueOrRange
                   ): ValueOrRange = {
    val gpuTotalEnergy = ValueOrRange * (gpuEnergy, gpuRequiredCount.toDouble)
    ValueOrRange * (gpuTotalEnergy, pue) match {
      case total => ValueOrRange * (Left(serverEnergy), pue) match {
        case Right(serverPart) => Right(RangeValue(serverPart.min + total.right.get.min, serverPart.max + total.right.get.max))
        case Left(serverPart) =>
          total match {
            case Left(gpuPart)   => Left(serverPart + gpuPart)
            case Right(gpuRange) => Right(RangeValue(serverPart + gpuRange.min, serverPart + gpuRange.max))
          }
      }
    }
  }

  def usageImpact(value: ValueOrRange, impactFactor: Double): ValueOrRange =
    ValueOrRange * (value, impactFactor)

  def embodiedImpact(
                      embodied: Double,
                      lifetime: Double,
                      latency: ValueOrRange
                    ): ValueOrRange =
    ValueOrRange / (ValueOrRange * (latency, embodied), lifetime)

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

  def computeLLMImpactsDag(
                            modelActiveParams: Double,
                            modelTotalParams: Double,
                            outputTokens: Double,
                            requestLatency: Double,
                            ifElectricityMixAdpe: Double,
                            ifElectricityMixPe: Double,
                            ifElectricityMixGwp: Double,
                            modelQuantBits: Int = Constants.ModelQuantizationBits,
                            gpuEnergyAlpha: Double = Constants.GpuEnergyAlpha,
                            gpuEnergyBeta: Double = Constants.GpuEnergyBeta,
                            gpuEnergyStdev: Double = Constants.GpuEnergyStdev,
                            gpuLatencyAlpha: Double = Constants.GpuLatencyAlpha,
                            gpuLatencyBeta: Double = Constants.GpuLatencyBeta,
                            gpuLatencyStdev: Double = Constants.GpuLatencyStdev,
                            gpuMemory: Double = Constants.GpuMemory,
                            gpuEmbodiedGwp: Double = Constants.GpuEmbodiedGWP,
                            gpuEmbodiedAdpe: Double = Constants.GpuEmbodiedADPe,
                            gpuEmbodiedPe: Double = Constants.GpuEmbodiedPE,
                            serverGpuCount: Int = Constants.ServerGPUs,
                            serverPower: Double = Constants.ServerPower,
                            serverEmbodiedGwp: Double = Constants.ServerEmbodiedGWP,
                            serverEmbodiedAdpe: Double = Constants.ServerEmbodiedADPe,
                            serverEmbodiedPe: Double = Constants.ServerEmbodiedPE,
                            serverLifetime: Double = Constants.HardwareLifespan,
                            datacenterPue: Double = Constants.DatacenterPUE
                          ): Map[String, ValueOrRange] = {

    val gpuEnergy = LLMImpactModel.gpuEnergy(modelActiveParams, outputTokens, gpuEnergyAlpha, gpuEnergyBeta, gpuEnergyStdev)
    val latency = LLMImpactModel.generationLatency(modelActiveParams, outputTokens, gpuLatencyAlpha, gpuLatencyBeta, gpuLatencyStdev, requestLatency)
    val memoryRequired = LLMImpactModel.modelRequiredMemory(modelTotalParams, modelQuantBits)
    val gpuRequired = LLMImpactModel.gpuRequiredCount(memoryRequired, gpuMemory)
    val latencyAsDouble = latency match {
      case Left(v) => v
      case Right(r) => r.max
    }
    val serverEnergy = LLMImpactModel.serverEnergy(latencyAsDouble, serverPower, serverGpuCount, gpuRequired)
    val requestEnergy = LLMImpactModel.requestEnergy(datacenterPue, serverEnergy, gpuRequired, Right(gpuEnergy))

    val usageGWP = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixGwp)
    val usageADPe = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixAdpe)
    val usagePE = LLMImpactModel.usageImpact(requestEnergy, ifElectricityMixPe)

    val embodiedGWPValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedGwp, serverGpuCount, gpuEmbodiedGwp, gpuRequired)
    val embodiedADPeValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedAdpe, serverGpuCount, gpuEmbodiedAdpe, gpuRequired)
    val embodiedPEValue = LLMImpactModel.combinedEmbodiedImpact(serverEmbodiedPe, serverGpuCount, gpuEmbodiedPe, gpuRequired)

    val embodiedGWP = LLMImpactModel.embodiedImpact(embodiedGWPValue, serverLifetime, latency)
    val embodiedADPe = LLMImpactModel.embodiedImpact(embodiedADPeValue, serverLifetime, latency)
    val embodiedPE = LLMImpactModel.embodiedImpact(embodiedPEValue, serverLifetime, latency)

    Map(
      "request_energy" -> requestEnergy,
      "request_usage_gwp" -> usageGWP,
      "request_usage_adpe" -> usageADPe,
      "request_usage_pe" -> usagePE,
      "request_embodied_gwp" -> embodiedGWP,
      "request_embodied_adpe" -> embodiedADPe,
      "request_embodied_pe" -> embodiedPE
    )
  }
}

object LLMImpactCalculator {

  def computeLLMImpacts(
                         modelActiveParams: ValueOrRange,
                         modelTotalParams: ValueOrRange,
                         outputTokens: Double,
                         ifElectricityMixAdpe: Double,
                         ifElectricityMixPe: Double,
                         ifElectricityMixGwp: Double,
                         requestLatency: Option[Double] = None,
                         extraParams: Map[String, Any] = Map.empty
                       ): Impacts = {

    val latency = requestLatency.getOrElse(Double.PositiveInfinity)

    val activeValues = modelActiveParams match {
      case Left(d)  => Seq(d)
      case Right(r) => Seq(r.min, r.max)
    }

    val totalValues = modelTotalParams match {
      case Left(d)  => Seq(d)
      case Right(r) => Seq(r.min, r.max)
    }

    val fields = Seq(
      "request_energy", "request_usage_gwp", "request_usage_adpe", "request_usage_pe",
      "request_embodied_gwp", "request_embodied_adpe", "request_embodied_pe"
    )

    val results = mutable.Map[String, ValueOrRange]()

    for ((act, tot) <- activeValues zip totalValues) {
      val res = LLMImpactExecutor.computeLLMImpactsDag(
        modelActiveParams = act,
        modelTotalParams = tot,
        outputTokens = outputTokens,
        requestLatency = latency,
        ifElectricityMixAdpe = ifElectricityMixAdpe,
        ifElectricityMixPe = ifElectricityMixPe,
        ifElectricityMixGwp = ifElectricityMixGwp
        // Pas de propagation de `extraParams` ici pour l'instant
      )

      fields.foreach { field =>
        results.get(field) match {
          case None =>
            results(field) = res(field)
          case Some(prev) =>
            val current = res(field)
            val merged = (prev, current) match {
              case (Left(x), Left(y)) => Right(RangeValue(min(x, y), max(x, y)))
              case (Right(r1), Right(r2)) =>
                Right(RangeValue(min(r1.min, r2.min), max(r1.max, r2.max)))
              case (Left(x), Right(r)) =>
                Right(RangeValue(min(x, r.min), max(x, r.max)))
              case (Right(r), Left(x)) =>
                Right(RangeValue(min(r.min, x), max(r.max, x)))
            }
            results(field) = merged
        }
      }
    }

    val energy = Energy(results("request_energy"))
    val gwpUsage = GWP(results("request_usage_gwp"))
    val adpeUsage = ADPe(results("request_usage_adpe"))
    val peUsage = PE(results("request_usage_pe"))
    val gwpEmbodied = GWP(results("request_embodied_gwp"))
    val adpeEmbodied = ADPe(results("request_embodied_adpe"))
    val peEmbodied = PE(results("request_embodied_pe"))

    Impacts(
      energy = energy,
      gwp = GWP(ValueOrRange * (gwpUsage.value, 1.0) match {
        case Left(v) => Left(v + gwpEmbodied.value.left.getOrElse(0.0))
        case Right(r1) => (gwpEmbodied.value match {
          case Left(v) => Right(RangeValue(r1.min + v, r1.max + v))
          case Right(r2) => Right(RangeValue(r1.min + r2.min, r1.max + r2.max))
        })
      }),
      adpe = ADPe(ValueOrRange * (adpeUsage.value, 1.0) match {
        case Left(v) => Left(v + adpeEmbodied.value.left.getOrElse(0.0))
        case Right(r1) => (adpeEmbodied.value match {
          case Left(v) => Right(RangeValue(r1.min + v, r1.max + v))
          case Right(r2) => Right(RangeValue(r1.min + r2.min, r1.max + r2.max))
        })
      }),
      pe = PE(ValueOrRange * (peUsage.value, 1.0) match {
        case Left(v) => Left(v + peEmbodied.value.left.getOrElse(0.0))
        case Right(r1) => (peEmbodied.value match {
          case Left(v) => Right(RangeValue(r1.min + v, r1.max + v))
          case Right(r2) => Right(RangeValue(r1.min + r2.min, r1.max + r2.max))
        })
      }),
      usage = Usage(
        energy = energy,
        gwp = gwpUsage,
        adpe = adpeUsage,
        pe = peUsage
      ),
      embodied = Embodied(
        gwp = gwpEmbodied,
        adpe = adpeEmbodied,
        pe = peEmbodied
      )
    )
  }
}

case class ImpactsOutput(
                          energy: Option[Energy] = None,
                          gwp: Option[GWP] = None,
                          adpe: Option[ADPe] = None,
                          pe: Option[PE] = None,
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
    "warnings" -> warnings.map(v => JsArray(v.map(_.json))).getOrElse(JsNull).asValue,
  )
}


case class Model(
  provider: String,
  name: String,
  architecture: Architecture,
  warnings: List[String],
  sources: List[String],
) {
  def hasWarnings: Boolean = warnings.nonEmpty
}
case class ParametersMoE(total: ValueOrRange, active: ValueOrRange)
case class ElectricityMix(name: String, adpe: Double, pe: Double, gwp: Double)
case class Architecture(typ: String, denseParameters: Option[ValueOrRange], moeParameters: Option[ParametersMoE])
object Architecture {
  val default = Architecture("dense", Some(Right(RangeValue(0, 0))), None)
  def from(json: JsObject): Architecture = try {
    val typ = json.select("type").asString
    Architecture(
      typ = typ,
      denseParameters = json.select("parameters").asOpt[JsObject].filter(_ => typ == "dense").filter(o => o.select("total").isEmpty).map { o =>
        Right(RangeValue(o.select("min").asInt, o.select("max").asInt))
      },
      moeParameters = json.select("parameters").asOpt[JsObject].filter(_ => typ == "moe").filter(o => o.select("total").isDefined).map { o =>
        o.select("active").asOpt[JsValue] match {
          case Some(active @ JsObject(_)) => {
            ParametersMoE(
              Left(o.select("total").as[JsNumber].value.toDouble),
              Right(RangeValue(
                active.select("min").asInt,
                active.select("max").asInt
              ))
            )
          }
          case Some(JsNumber(active)) => {
            ParametersMoE(
              Left(o.select("total").as[JsNumber].value.toDouble),
              Right(RangeValue(
                active.toInt,
                active.toInt
              ))
            )
          }
          case _ => ParametersMoE(
            Left(o.select("total").as[JsNumber].value.toDouble),
            Right(RangeValue(
              o.select("min").asInt,
              o.select("max").asInt
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

  val models_by_provider_name: Map[String, Model] = {
    val modelsJson = Json.parse(getResourceCode("data/eg-models.json"))
    val rawModels = modelsJson.select("models").as[Seq[JsObject]].map { obj =>
      val provider = obj.select("provider").asString
      val name = obj.select("name").asString
      (s"${provider}-${name}", Model(
        provider = provider,
        name = name,
        warnings = obj.select("warnings").asOpt[List[String]].getOrElse(List.empty),
        sources = obj.select("sources").asOpt[List[String]].getOrElse(List.empty),
        architecture = Architecture.from(obj.select("architecture").asObject)
      ))
    }
    rawModels.toMap
  }

  val electricityMixes = {
    val csv = getResourceCode("data/eg-elec.csv")
    csv.split("\n")
      .toSeq
      .tail
      .map { line =>
        val parts = line.split(",")
        (parts(0), ElectricityMix(name = parts(0), adpe = parts(1).toDouble, pe = parts(2).toDouble, gwp = parts(3).toDouble))
      }
      .toMap
  }

  def getResourceCode(path: String): String = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
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
        val (totalParams, activeParams) = model.architecture.moeParameters match {
          case Some(moe) => (moe.total, moe.active)
          case _ => (model.architecture.denseParameters.get, model.architecture.denseParameters.get)
        }

        val mix = electricityMixes.getOrElse(electricityMixZoneOpt.getOrElse(settings.electricityMixZone), electricityMixes.head._2)

        val result = LLMImpactCalculator.computeLLMImpacts(
          modelActiveParams = activeParams,
          modelTotalParams = totalParams,
          outputTokens = outputTokenCount,
          requestLatency = Some(requestLatency),
          ifElectricityMixAdpe = mix.adpe,
          ifElectricityMixPe = mix.pe,
          ifElectricityMixGwp = mix.gwp
        )

        val output = ImpactsOutput(
          energy = Some(result.energy),
          gwp = Some(result.gwp),
          adpe = Some(result.adpe),
          pe = Some(result.pe),
          usage = Some(result.usage),
          embodied = Some(result.embodied)
        )

        output.right
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
    if (allowConfigOverride) originalBody.select("model").asOptString.getOrElse(chatClient.model.get) else chatClient.model.get
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
    }
  }

  private def handleStream(attrs: TypedMap, originalBody: JsValue)(f: => Future[Either[JsValue, Source[ChatResponseChunk, _]]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
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
              resp.applyOnIf(addCostsInResp) { src =>
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
                  requestLatency = System.currentTimeMillis() - start,
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

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    getProvider() match {
      case None => chatClient.call(prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        val start = System.currentTimeMillis()
        chatClient.call(prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            ext.llmImpacts.llmImpacts(
              provider = originalProvider.metadata.getOrElse("eco-impacts-provider", provider),
              modelName = originalProvider.metadata.getOrElse("eco-impacts-model", getModel(originalBody)),
              outputTokenCount = (usage.generationTokens + usage.reasoningTokens).toInt,
              requestLatency = System.currentTimeMillis() - start,
              electricityMixZoneOpt = originalProvider.metadata.get("eco-impacts-electricity-mix-zone"),
            ) match {
              case Left(err) => Right(resp)
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

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    handleStream(attrs, originalBody) {
      chatClient.stream(prompt, attrs, originalBody)
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    getProvider() match {
      case None => chatClient.completion(prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        val start = System.currentTimeMillis()
        chatClient.completion(prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            ext.llmImpacts.llmImpacts(
              provider = originalProvider.metadata.getOrElse("eco-impacts-provider", provider),
              modelName = originalProvider.metadata.getOrElse("eco-impacts-model", getModel(originalBody)),
              outputTokenCount = (usage.generationTokens + usage.reasoningTokens).toInt,
              requestLatency = System.currentTimeMillis() - start,
              electricityMixZoneOpt = originalProvider.metadata.get("eco-impacts-electricity-mix-zone"),
            ) match {
              case Left(err) => Right(resp)
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

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    handleStream(attrs, originalBody) {
      chatClient.completionStream(prompt, attrs, originalBody)
    }
  }
}

