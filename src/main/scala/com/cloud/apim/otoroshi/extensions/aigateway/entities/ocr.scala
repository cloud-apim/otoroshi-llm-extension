package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.OcrModelClient
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

case class OcrModel(
                     location: EntityLocation,
                     id: String,
                     name: String,
                     description: String,
                     tags: Seq[String],
                     metadata: Map[String, String],
                     provider: String,
                     config: JsObject,
                     models: ModelSettings = ModelSettings.empty
                   ) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = OcrModel.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

  def getOcrModelClient()(implicit env: Env): Option[OcrModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val _token = connection.select("token").asOpt[String].getOrElse("xxx")
    val token = if (_token.contains(",")) {
      val parts = _token.split(",").map(_.trim)
      val index = AiProvider.tokenCounter.incrementAndGet() % (if (parts.nonEmpty) parts.length else 1)
      parts(index)
    } else {
      _token
    }
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    provider.toLowerCase() match {
      case "alphaedge" => {
        val api = new AlphaEdgeApi(baseUrl.getOrElse(AlphaEdgeApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        new AlphaEdgeOcrModelClient(api, AlphaEdgeOcrModelClientOptions.fromJson(options), id).some
      }
      case "mistral" => {
        val api = new MistralAiApi(baseUrl.getOrElse(MistralAiApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        new MistralOcrModelClient(api, MistralOcrModelClientOptions.fromJson(options), id).some
      }
      case _ => None
    }
  }
}

object OcrModel {
  val format = new Format[OcrModel] {
    override def writes(o: OcrModel): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "provider" -> o.provider,
      "config" -> o.config,
      "models" -> o.models.json
    )

    override def reads(json: JsValue): JsResult[OcrModel] = Try {
      OcrModel(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
        models = ModelSettings.format.reads((json \ "models").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(ModelSettings.empty)
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "OcrModel",
      "ocr-models",
      "ocr-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[OcrModel](
        format = OcrModel.format,
        clazz = classOf[OcrModel],
        keyf = id => datastores.ocrModelsDataStore.key(id),
        extractIdf = c => datastores.ocrModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("mistral") => OcrModel(
              id = IdGenerator.namedId("ocr-model", env),
              name = "Mistral OCR",
              description = "A Mistral OCR model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "mistral",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> MistralAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "mistral-ocr-latest"
                )
              ),
            ).json
            case _ => OcrModel(
              id = IdGenerator.namedId("ocr-model", env),
              name = "AlphaEdge OCR",
              description = "An AlphaEdge OCR model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "alphaedge",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> AlphaEdgeApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> AlphaEdgeApi.defaultOcrModel
                )
              ),
            ).json
          }
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allOcrModels(),
        stateOne = id => states.ocrModel(id),
        stateUpdate = values => states.updateOcrModels(values)
      )
    )
  }
}

trait OcrModelsDataStore extends BasicStore[OcrModel]

class KvOcrModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends OcrModelsDataStore
    with RedisLikeStore[OcrModel] {
  override def fmt: Format[OcrModel] = OcrModel.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:ocrmodels:$id"

  override def extractId(value: OcrModel): String = value.id
}
