package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.utils.TypedMap
import play.api.libs.typedmap.TypedKey

// A provider can hand the call it received over to another provider: its fallback, the target of a load
// balancer, the candidate of a router. The whole chain of that provider then runs inside its own, and prices
// the call, counts it against budgets and audits it. On the way back, the provider that handed the call over
// must not price it and count it a second time, with its own price.
object HandOver {

  private val Key = TypedKey[java.util.Set[String]]("cloud-apim.ai-gateway.HandedOver")

  // a call to `provider` starts: what an earlier call sharing these attributes did is not about this one
  def start(attrs: TypedMap, provider: AiProvider): Unit = attrs.get(Key).foreach(_.remove(provider.id))

  // `provider` hands the call over to another provider
  def mark(attrs: TypedMap, provider: AiProvider): Unit = {
    attrs.putIfAbsent(Key -> java.util.concurrent.ConcurrentHashMap.newKeySet[String]())
    attrs.get(Key).foreach(_.add(provider.id))
  }

  // whether `provider` handed the call at hand over, the provider that took it being the one that accounts for it
  def by(attrs: TypedMap, provider: AiProvider): Boolean = attrs.get(Key).exists(_.contains(provider.id))
}

// The opposite of a hand over: a call made on behalf of another one, which stays the call of its provider. A
// guardrail asking a model what it thinks of the messages, a workflow, a model emulated by another one. Auditing
// a model writes the provider and the model budgets are scoped on, its usage and its cost in the attributes of
// its call: with the attributes of the outer call, the outer call would be counted for the inner model.
object ChildCall {

  // who is calling, and nothing of what the decorators of the outer call wrote
  def attrs(outer: TypedMap): TypedMap = {
    val child = TypedMap.empty
    outer.get(otoroshi.plugins.Keys.ApiKeyKey).foreach(v => child.put(otoroshi.plugins.Keys.ApiKeyKey -> v))
    outer.get(otoroshi.plugins.Keys.UserKey).foreach(v => child.put(otoroshi.plugins.Keys.UserKey -> v))
    outer.get(otoroshi.next.plugins.Keys.RouteKey).foreach(v => child.put(otoroshi.next.plugins.Keys.RouteKey -> v))
    outer.get(otoroshi.plugins.Keys.RequestKey).foreach(v => child.put(otoroshi.plugins.Keys.RequestKey -> v))
    outer.get(otoroshi.plugins.Keys.SnowFlakeKey).foreach(v => child.put(otoroshi.plugins.Keys.SnowFlakeKey -> v))
    child
  }
}
