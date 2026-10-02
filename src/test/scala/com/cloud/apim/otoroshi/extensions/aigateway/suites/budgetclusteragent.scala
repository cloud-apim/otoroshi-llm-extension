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
}
