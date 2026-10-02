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
