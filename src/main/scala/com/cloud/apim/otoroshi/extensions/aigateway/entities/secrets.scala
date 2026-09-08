package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.models.EntityLocationSupport
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue}

/**
 * Provider entities carry the credentials used to reach the provider, and those entities are serialized whole
 * into the audit events (`provider_details`). Anything shipped to a data exporter therefore used to carry the
 * api keys with it.
 *
 * Redacting them downstream - in an exporter transform, say - would be too late and too fragile: the secret
 * would already have been through the event pipeline, and any exporter configured without the transform would
 * leak it. So the credentials never make it into an event in the first place.
 */
object EntitySecrets {

  val marker = "**redacted**"

  // keys whose value is a credential wherever they appear in an entity. Matched case insensitively, at any
  // depth, since the shape differs between entity kinds (`connection.token`, `config.connection.api_key`, ...)
  val secretKeys: Set[String] = Set(
    "token", "api_key", "apikey", "api-key", "secret", "client_secret", "secret_key",
    "password", "access_key", "private_key", "authorization", "x-api-key", "otoroshi-client-id", "otoroshi-client-secret"
  )

  def redact(json: JsValue): JsValue = json match {
    case JsObject(fields) => JsObject(fields.map {
      case (key, _) if secretKeys.contains(key.toLowerCase) => (key, JsString(marker))
      case (key, value) => (key, redact(value))
    })
    case JsArray(values) => JsArray(values.map(redact))
    case other => other
  }
}

extension (entity: EntityLocationSupport) {
  /** the entity as it can safely appear in an event: same thing, minus the credentials */
  def redactedJson: JsValue = EntitySecrets.redact(entity.json)
}
