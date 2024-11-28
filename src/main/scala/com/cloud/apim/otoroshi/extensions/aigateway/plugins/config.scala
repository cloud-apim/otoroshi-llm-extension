package com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.ChatMessage
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgPluginConfig
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import scala.util.{Failure, Success, Try}

object AiPluginsKeys {
  val PromptTemplateKey = TypedKey[String]("cloud-apim.ai-gateway.PromptTemplate")
  val PromptContextKey = TypedKey[(Seq[JsObject], Seq[JsObject])]("cloud-apim.ai-gateway.PromptContexts")
  val PromptValidatorsKey = TypedKey[Seq[PromptValidatorConfig]]("cloud-apim.ai-gateway.PromptValidators")
}

case class AiPromptRequestConfig(ref: String = "", _prompt: String = "", promptRef: Option[String] = None, contextRef: Option[String] = None, extractor: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = AiPromptRequestConfig.format.writes(this)
  def preChatMessages(implicit env: Env): Seq[ChatMessage] = {
    contextRef match {
      case None => Seq.empty
      case Some(ref) => env.adminExtensions.extension[AiExtension] match {
        case None => Seq.empty
        case Some(ext) => ext.states.context(ref) match {
          case None => Seq.empty
          case Some(context) => context.preChatMessages()
        }
      }
    }
  }
  def postChatMessages(implicit env: Env): Seq[ChatMessage] = {
    contextRef match {
      case None => Seq.empty
      case Some(ref) => env.adminExtensions.extension[AiExtension] match {
        case None => Seq.empty
        case Some(ext) => ext.states.context(ref) match {
          case None => Seq.empty
          case Some(context) => context.postChatMessages()
        }
      }
    }
  }
  def prompt(implicit env: Env): String = promptRef match {
    case None => _prompt
    case Some(ref) => env.adminExtensions.extension[AiExtension] match {
      case None => _prompt
      case Some(ext) => ext.states.prompt(ref) match {
        case None => _prompt
        case Some(prompt) => prompt.prompt
      }
    }
  }
}

object AiPromptRequestConfig {
  val default = AiPromptRequestConfig()
  val format = new Format[AiPromptRequestConfig] {
    override def reads(json: JsValue): JsResult[AiPromptRequestConfig] = Try {
      AiPromptRequestConfig(
        ref = json.select("ref").asOpt[String].getOrElse(""),
        _prompt = json.select("prompt").asOpt[String].getOrElse(""),
        promptRef = json.select("prompt_ref").asOpt[String].filterNot(_.isBlank),
        contextRef = json.select("context_ref").asOpt[String].filterNot(_.isBlank),
        extractor = json.select("extractor").asOpt[String],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AiPromptRequestConfig): JsValue = Json.obj(
      "ref" -> o.ref,
      "prompt" -> o._prompt,
      "prompt_ref" -> o.promptRef.map(_.json).getOrElse(JsNull).asValue,
      "context_ref" -> o.contextRef.map(_.json).getOrElse(JsNull).asValue,
      "extractor" -> o.extractor.map(_.json).getOrElse(JsNull).asValue,
    )
  }
  val configFlow: Seq[String] = Seq("ref", "prompt", "prompt_ref", "context_ref", "extractor")
  def configSchema(what: String): Option[JsObject] = Some(Json.obj(
    "prompt" -> Json.obj(
      "type" -> "text",
      "label" -> s"${what} prompt"
    ),
    "prompt_ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"${what} prompt ref.",
      "props" -> Json.obj(
        "isClearable" -> true,
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "context_ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"${what} context ref.",
      "props" -> Json.obj(
        "isClearable" -> true,
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "extractor" -> Json.obj(
      "type" -> "string",
      "suffix" -> "regex",
      "label" -> "Response extractor"
    ),
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"AI LLM Provider",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
}

case class PromptValidatorConfig(allow: Seq[String] = Seq.empty, deny: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = PromptValidatorConfig.format.writes(this)
  def validate(content: String): Boolean = {
    val allowed = if (allow.isEmpty) true else allow.exists(al => RegexPool.regex(al).matches(content))
    val denied = if (deny.isEmpty) false else deny.exists(dn => RegexPool.regex(dn).matches(content))
    !denied && allowed
  }
}

object PromptValidatorConfig {
  val default = PromptValidatorConfig()
  val format = new Format[PromptValidatorConfig] {
    override def reads(json: JsValue): JsResult[PromptValidatorConfig] = Try {
      PromptValidatorConfig(
        allow = json.select("allow").asOpt[Seq[String]].getOrElse(Seq.empty),
        deny = json.select("deny").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: PromptValidatorConfig): JsValue = Json.obj(
      "allow" -> o.allow,
      "deny" -> o.deny,
    )
  }
}

case class AiPluginRefConfig(ref: String) extends NgPluginConfig {
  def json: JsValue = AiPluginRefConfig.format.writes(this)
}

object AiPluginRefConfig {
  val configFlow: Seq[String] = Seq("ref")
  def configSchema(what: String, name: String): Option[JsObject] = Some(Json.obj(
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"AI ${what}",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/${name}",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = AiPluginRefConfig("")
  val format = new Format[AiPluginRefConfig] {
    override def writes(o: AiPluginRefConfig): JsValue = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[AiPluginRefConfig] = Try {
      AiPluginRefConfig(
        ref = json.select("ref").asOpt[String].getOrElse("")
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}


case class AiPluginRefsConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = AiPluginRefsConfig.format.writes(this)
}

object AiPluginRefsConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema(what: String, name: String): Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"AI ${what}",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/${name}",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = AiPluginRefsConfig(Seq.empty)
  val format = new Format[AiPluginRefsConfig] {
    override def writes(o: AiPluginRefsConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[AiPluginRefsConfig] = Try {
      val singleRef = json.select("ref").asOpt[String].map(r => Seq(r)).getOrElse(Seq.empty)
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      val allRefs = refs ++ singleRef
      AiPluginRefsConfig(
        refs = allRefs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}