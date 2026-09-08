package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.*
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.Types.ValueOrRange
import play.api.libs.json.Json

/**
 * The port of ecologits is only worth anything if it produces the same numbers as ecologits. The expected
 * values below are computed from the upstream formulas (ecologits/impacts/llm.py on main), not from a previous
 * run of this code - otherwise the test would only pin down whatever the port happens to do today.
 */
class EcologitsSuite extends munit.FunSuite {

  // the WOR (world) electricity mix, the default zone
  val worAdpe = 1.349e-08
  val worPe = 2.5871
  val worGwp = 0.45829
  val worWue = 3.908

  // cohere runs in a datacenter we have measurements for
  val pue: ValueOrRange = Left(1.09)
  val dcWue: ValueOrRange = Left(0.999)

  def scalar(v: ValueOrRange): Double = v match {
    case Left(d) => d
    case Right(r) => throw new AssertionError(s"expected a single value, got a range ${r.min}..${r.max}")
  }

  def close(actual: Double, expected: Double, what: String): Unit = {
    val tolerance = math.abs(expected) * 1e-9
    assert(math.abs(actual - expected) <= tolerance, s"$what: got $actual, expected $expected")
  }

  def impactsOf(active: Double, total: Double, tokens: Double, latency: Double, tps: Option[Double], ttft: Option[Double]): Impacts =
    LLMImpactCalculator.computeLLMImpacts(
      modelActiveParams = Left(active),
      modelTotalParams = Left(total),
      outputTokens = tokens,
      ifElectricityMixAdpe = worAdpe,
      ifElectricityMixPe = worPe,
      ifElectricityMixGwp = worGwp,
      ifElectricityMixWue = worWue,
      datacenterPue = pue,
      datacenterWue = dcWue,
      requestLatency = Some(latency),
      tps = tps,
      ttft = ttft,
    )

  test("a model shipping measured tps and ttft is billed on them, not on the fitted formula") {
    val impacts = impactsOf(32.3, 32.3, 100, 1000.0, Some(26.8), Some(0.59))
    close(scalar(impacts.energy.value), 9.489005393763905e-06, "energy")
    close(scalar(impacts.usage.gwp.value), 4.34871628190806e-06, "usage gwp")
    close(scalar(impacts.usage.adpe.value), 1.2800668276187508e-13, "usage adpe")
    close(scalar(impacts.usage.pe.value), 2.4549005854206597e-05, "usage pe")
    close(scalar(impacts.usage.wcf.value), 4.577983710485699e-05, "usage wcf")
    close(scalar(impacts.embodied.gwp.value), 7.033436333955222e-07, "embodied gwp")
    close(scalar(impacts.embodied.adpe.value), 3.939580777618755e-11, "embodied adpe")
    close(scalar(impacts.embodied.pe.value), 8.900455050304981e-06, "embodied pe")
    // the totals are usage plus embodied, and water has no embodied counterpart
    close(scalar(impacts.gwp.value), 4.34871628190806e-06 + 7.033436333955222e-07, "total gwp")
    close(scalar(impacts.wcf.value), 4.577983710485699e-05, "total wcf")
  }

  test("the model cannot have taken longer than the request, so the latency is capped") {
    val impacts = impactsOf(32.3, 32.3, 100, 1.0, Some(26.8), Some(0.59))
    // same model and token count as above, but a one second request: everything scales down to it
    close(scalar(impacts.energy.value), 7.132062568826094e-06, "energy")
    close(scalar(impacts.usage.wcf.value), 3.440873401095152e-05, "usage wcf")
    close(scalar(impacts.embodied.gwp.value), 1.6276041666666666e-07, "embodied gwp")
  }

  test("a model with no measured deployment falls back to the fitted latency") {
    // 132B total means 4 gpus, which changes both the server share and the embodied part
    val impacts = impactsOf(36, 132, 100, 1000.0, None, None)
    close(scalar(impacts.energy.value), 4.473624192933929e-05, "energy")
    close(scalar(impacts.usage.gwp.value), 2.05021723137969e-05, "usage gwp")
    close(scalar(impacts.usage.wcf.value), 0.00021583061482445423, "usage wcf")
    close(scalar(impacts.embodied.pe.value), 5.261468466750224e-05, "embodied pe")
  }

  test("gpus are allocated by powers of two") {
    def count(totalParams: Double): Int =
      LLMImpactModel.gpuRequiredCount(LLMImpactModel.modelRequiredMemory(totalParams, Constants.ModelQuantizationBits), Constants.GpuMemory)
    assertEquals(count(7), 1)      // 16.8 GB, one gpu
    assertEquals(count(32.3), 1)   // 77.5 GB, still one gpu
    assertEquals(count(70), 4)     // 168 GB needs 3 gpus, rounded up to 4
    assertEquals(count(132), 4)    // 316.8 GB needs 4
    assertEquals(count(405), 16)   // 972 GB needs 13, rounded up to 16
  }

  test("a parameter count given as a range yields a range, from the ends") {
    val ranged = LLMImpactCalculator.computeLLMImpacts(
      modelActiveParams = Right(RangeValue(70, 120)),
      modelTotalParams = Right(RangeValue(70, 120)),
      outputTokens = 100,
      ifElectricityMixAdpe = worAdpe, ifElectricityMixPe = worPe, ifElectricityMixGwp = worGwp, ifElectricityMixWue = worWue,
      datacenterPue = pue, datacenterWue = dcWue,
      requestLatency = Some(1000.0), tps = Some(39.4), ttft = Some(1.04),
    )
    val energy = ranged.energy.value
    assert(energy.isRight, s"a ranged model should report a range, got ${energy}")
    val range = energy.toOption.get
    assert(range.min < range.max, s"the range should be ordered, got ${range.min}..${range.max}")
    // both ends must match the single valued runs at each end
    val low = impactsOf(70, 70, 100, 1000.0, Some(39.4), Some(1.04))
    val high = impactsOf(120, 120, 100, 1000.0, Some(39.4), Some(1.04))
    close(range.min, scalar(low.energy.value), "range low end")
    close(range.max, scalar(high.energy.value), "range high end")
  }

  test("an override can still be given in the old csv shape") {
    val legacy = ElectricityMix.parse("name,adpe,pe,gwp,wue\nZZZ,1.0,2.0,3.0,4.0\n")
    assertEquals(legacy("ZZZ").gwp, 3.0)
    assertEquals(legacy("ZZZ").wue, 4.0)
    // and a csv without the water column stays readable
    assertEquals(ElectricityMix.parse("name,adpe,pe,gwp\nZZZ,1.0,2.0,3.0\n")("ZZZ").wue, 0.0)
    // json is the shape upstream ships, so it is what an override should use now
    val json = ElectricityMix.parse("""{"electricity_mixes":[{"name":"ZZZ","adpe":1.0,"pe":2.0,"gwp":3.0,"wue":4.0,"warnings":["electricity-mix-wue-world"]}]}""")
    assertEquals(json("ZZZ").wue, 4.0)
    assertEquals(json("ZZZ").warnings, List("electricity-mix-wue-world"))
  }

  test("the constants are the upstream ones") {
    // these are not tuning knobs: they come from ecologits and must move with it
    assertEquals(Constants.ModelQuantizationBits, 16)
    assertEquals(Constants.BatchSize, 64.0)
    assertEquals(Constants.GpuEmbodiedGWP, 273.0)
    assertEquals(Constants.GpuEmbodiedADPe, 0.00895)
    assertEquals(Constants.GpuEmbodiedPE, 3721.0)
    assertEquals(Constants.ServerPower, 1.2)
    assertEquals(Constants.ServerEmbodiedGWP, 5700.0)
    assertEquals(Constants.HardwareLifespan, 3 * 365 * 24 * 60 * 60)
  }

  test("the shipped datasets are the upstream ones") {
    val models = Json.parse(scala.io.Source.fromFile("src/main/resources/data/eg-models.json").mkString)
    val entries = (models \ "models").as[Seq[play.api.libs.json.JsObject]]
    assert(entries.size > 300, s"expected the full model list, got ${entries.size}")
    assert(entries.forall(e => (e \ "deployment").isDefined), "every model should carry its deployment block")

    // the mix file is the upstream document verbatim, so a refresh is a copy and nothing is lost on the way
    val mixes = ElectricityMix.fromDocument(scala.io.Source.fromFile("src/main/resources/data/eg-elec.json").mkString)
    assert(mixes.size > 200, s"expected the full zone list, got ${mixes.size} zones")
    val wor = mixes("WOR")
    assertEquals(wor.wue, worWue, "the world zone water factor")
    assertEquals(wor.gwp, worGwp)
    // a zone whose factors are the world ones says so, which the csv could not carry
    assert(mixes.values.count(_.warnings.nonEmpty) > 100, "most zones have at least one estimated factor")
    assert(mixes("FRA").warnings.isEmpty, "france is fully measured")
  }
}
