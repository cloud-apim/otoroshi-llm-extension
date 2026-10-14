package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiBudgetClusterAgent
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.util.UUID
import java.util.concurrent.atomic.{AtomicLong, DoubleAdder}

// A worker counts what its calls consume and pushes it to the leaders from time to time. Calls keep coming while a
// push is on its way: what they consume is for the next push. No gateway needed: the counters are the worker's own.
class BudgetClusterAgentSuite extends munit.FunSuite {

  private val agent = AiBudgetClusterAgent

  // a budget in its cycle, as the counters name it
  private def budget(cycle: String = "0"): String = s"budget_${UUID.randomUUID()}:${cycle}"

  private def consume(key: String, tokens: Long, usd: Double): Unit = {
    agent.totalTokensCounters.getOrElseUpdate(key, new AtomicLong()).addAndGet(tokens)
    agent.totalUsdCounters.getOrElseUpdate(key, new DoubleAdder()).add(usd)
  }

  // what the next push would tell the leaders about a budget
  private def pending(key: String): (Option[Long], Option[BigDecimal]) = {
    val deltas = agent.deltas()
    (deltas.select("total_tokens").select(key).asOpt[Long], deltas.select("total_usd").select(key).asOpt[BigDecimal])
  }

  test("what is consumed while a push is on its way goes with the next one") {
    val key = budget()
    consume(key, 100, 0.5)
    val pushed = agent.deltas()
    assertEquals(pushed.select("total_tokens").select(key).asOpt[Long], Some(100L))
    consume(key, 40, 0.25)
    agent.acknowledge(pushed, _ => true)
    assertEquals(pending(key), (Some(40L), Some(BigDecimal("0.25"))))
  }

  test("what the leaders counted is not pushed again") {
    val key = budget()
    consume(key, 100, 0.1 + 0.2)
    agent.acknowledge(agent.deltas(), _ => true)
    assertEquals(pending(key), (None, None))
  }

  test("a push the leaders did not take is pushed again, with what came since") {
    val key = budget()
    consume(key, 100, 0.5)
    agent.deltas()
    consume(key, 40, 0.25)
    assertEquals(pending(key), (Some(140L), Some(BigDecimal("0.75"))))
  }

  test("the counters of a cycle that is over are dropped once pushed, the ones of the current cycle are kept") {
    val over = budget("3")
    val current = budget("4")
    Seq(over, current).foreach(consume(_, 100, 0.5))
    agent.acknowledge(agent.deltas(), key => key == current)
    assert(!agent.totalTokensCounters.contains(over) && !agent.totalUsdCounters.contains(over), "nothing will be added to a cycle that is over")
    assert(agent.totalTokensCounters.contains(current) && agent.totalUsdCounters.contains(current), "the current cycle goes on")
    consume(current, 10, 0.125)
    assertEquals(pending(current), (Some(10L), Some(BigDecimal("0.125"))))
  }

  // a budget counting its consumers apart: what each of them consumed
  private def consumeApart(budget: String, partition: String, tokens: Long, usd: Double, cycle: String = "0"): Unit = {
    agent.partitionedUnitCounters.getOrElseUpdate((budget, cycle, partition, "total_tokens"), new AtomicLong()).addAndGet(tokens)
    agent.partitionedUsdCounters.getOrElseUpdate((budget, cycle, partition, "total_usd"), new DoubleAdder()).add(usd)
  }

  private def pendingApart(budget: String, partition: String): Option[JsObject] =
    agent.deltas().select("partitioned").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      .find(e => e.select("budget").asOpt[String].contains(budget) && e.select("partition").asOpt[String].contains(partition))
      .map(_.select("counters").as[JsObject])

  test("what a budget counting its consumers apart consumed is pushed by partition, in a section of its own") {
    val id = s"budget_${UUID.randomUUID()}"
    consumeApart(id, "alice@acme.io", 100, 0.5)
    consumeApart(id, "bob@acme.io", 10, 0.25)
    val pushed = agent.deltas()
    val entries = pushed.select("partitioned").as[Seq[JsObject]].filter(_.select("budget").asString == id)
    assertEquals(entries.map(e => (e.select("cycle").asString, e.select("partition").asString, e.select("counters").select("total_tokens").as[Long], e.select("counters").select("total_usd").as[BigDecimal])),
      Seq(("0", "alice@acme.io", 100L, BigDecimal("0.5")), ("0", "bob@acme.io", 10L, BigDecimal("0.25"))))
    assert(!pushed.select("total_tokens").asOpt[JsObject].exists(_.keys.exists(_.startsWith(id))), "nor in the sections of the budgets counted as a whole")
    // a leader of a previous version does not read the section: what it held is pushed again
    agent.acknowledge(pushed, _ => true)
    assertEquals(pendingApart(id, "alice@acme.io").flatMap(_.select("total_tokens").asOpt[Long]), Some(100L))
    // a leader that read it says so: what came since goes with the next push
    consumeApart(id, "alice@acme.io", 40, 0.0)
    agent.acknowledgePartitioned(pushed, (_, _) => true)
    assertEquals(pendingApart(id, "alice@acme.io").map(c => (c.select("total_tokens").asOpt[Long], c.select("total_usd").asOpt[BigDecimal])), Some((Some(40L), None)))
    assertEquals(pendingApart(id, "bob@acme.io"), None)
    // once the cycle is over, nothing is kept of it
    agent.acknowledgePartitioned(agent.deltas(), (_, _) => false)
    assert(!agent.partitionedUnitCounters.keys.exists(_._1 == id) && !agent.partitionedUsdCounters.keys.exists(_._1 == id))
  }
}
