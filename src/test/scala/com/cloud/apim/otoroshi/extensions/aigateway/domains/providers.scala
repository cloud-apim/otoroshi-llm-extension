package com.cloud.apim.otoroshi.extensions.aigateway.domains

import com.cloud.apim.otoroshi.extensions.aigateway.OtoroshiClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import munit.Assertions
import otoroshi.utils.syntax.implicits.BetterFuture

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object LlmProviderUtils extends Assertions {

  def createProvider(client: OtoroshiClient)(provider: AiProvider)(implicit ec: ExecutionContext) = {
    val providerRes = client.forLlmEntity("providers").createEntity(provider).awaitf(10.seconds)
    assert(providerRes.created, s"[${provider.provider}] provider has not been created")
  }

  def upsertProvider(client: OtoroshiClient)(provider: AiProvider)(implicit ec: ExecutionContext) = {
    val providerRes = client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds)
    assert(providerRes.createdOrUpdated, s"[${provider.provider}] provider has not been created/updated")
  }

}
