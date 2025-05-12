package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.AudioModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

case class AudioModel(
                       location: EntityLocation,
                       id: String,
                       name: String,
                       description: String,
                       tags: Seq[String],
                       metadata: Map[String, String],
                       provider: String,
                       config: JsObject,
                     ) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = AudioModel.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

  def getAudioModelClient()(implicit env: Env): Option[AudioModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val ttsOptions = config.select("tts").asOpt[JsObject].getOrElse(Json.obj())
    val sttOptions = config.select("stt").asOpt[JsObject].getOrElse(Json.obj())
    provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(OpenAiApi.baseUrl, token, timeout.getOrElse(30.seconds), providerName = "OpenAI", env = env)
        val ttsopts = OpenAIAudioModelClientTtsOptions.fromJson(ttsOptions)
        val sttopts = OpenAIAudioModelClientSttOptions.fromJson(sttOptions)
        new OpenAIAudioModelClient(api, ttsopts, sttopts, id).some
      }
      case "groq" => {
        val api = new GroqApi(GroqApi.baseUrl, token, timeout.getOrElse(30.seconds), env = env)
        val ttsopts = GroqAudioModelClientTtsOptions.fromJson(ttsOptions)
        val sttopts = GroqAudioModelClientSttOptions.fromJson(sttOptions)
        new GroqAudioModelClient(api, ttsopts, sttopts, id).some
      }
      case "elevenlabs" => {
        val api = new ElevenLabsApi(ElevenLabsApi.baseUrl, token, timeout.getOrElse(30.seconds), env = env)
        val ttsopts = ElevenLabsAudioModelClientTtsOptions.fromJson(ttsOptions)
        val sttopts = ElevenLabsAudioModelClientSttOptions.fromJson(ttsOptions)
        new ElevenLabsAudioModelClient(api, ttsopts, sttopts, id).some
      }
      case _ => None
    }
  }
}

object AudioModel {
  val format = new Format[AudioModel] {
    override def writes(o: AudioModel): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "provider" -> o.provider,
      "config" -> o.config,
    )

    override def reads(json: JsValue): JsResult[AudioModel] = Try {
      AudioModel(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "AudioModel",
      "audio-models",
      "audio-models",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AudioModel](
        format = AudioModel.format,
        clazz = classOf[AudioModel],
        keyf = id => datastores.AudioModelsDataStore.key(id),
        extractIdf = c => datastores.AudioModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => AudioModel(
              id = IdGenerator.namedId("audio-model", env),
              name = "OpenAI text-AudioGen-3-small",
              description = "An OpenAI AudioGen model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              config = Json.obj(
                "connection" -> Json.obj(
                  "token" -> "xxxxx",
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "tts" -> Json.obj(
                    "model" -> "gpt-4o-mini-tts",
                    "response_format" -> "mp3",
                    "speed" -> 1
                  ),
                  "stt" -> {
                    "model" -> "gpt-4o-mini-transcribe"
                  }
                )
              ),
            ).json
            case _ => AudioModel(
              id = IdGenerator.namedId("audio-model", env),
              name = "Local audio model",
              description = "A Local audio model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "all-minilm-l6-v2",
              config = Json.obj(),
            ).json
          }

        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allAudioModel(),
        stateOne = id => states.audioModel(id),
        stateUpdate = values => states.updateAudioModel(values)
      )
    )
  }
}

trait AudioModelsDataStore extends BasicStore[AudioModel]

class KvAudioModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends AudioModelsDataStore
    with RedisLikeStore[AudioModel] {
  override def fmt: Format[AudioModel] = AudioModel.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:audiomodels:$id"

  override def extractId(value: AudioModel): String = value.id
}
