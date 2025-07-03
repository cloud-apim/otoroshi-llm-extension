package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.ImageModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ImageModelClientDecorators
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

case class ImageModel(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  provider: String,
  config: JsObject,
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ImageModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")
  def getImageModelClient()(implicit env: Env): Option[ImageModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val genOptions = config.select("options").select("generation").asOpt[JsObject].getOrElse(Json.obj())
    val editOptions = config.select("options").select("edition").asOpt[JsObject].getOrElse(Json.obj())
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
    val rawClient = provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "OpenAI", env = env)
        val opts = OpenAiImageModelClientOptions.fromJson(genOptions)
        val editOpts = OpenAiImageEditionModelClientOptions.fromJson(editOptions)
        new OpenAiImageModelClient(api, opts, editOpts, id).some
      }
      case "x-ai" => {
        val api = new XAiApi(baseUrl.getOrElse(XAiApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = XAiImageModelClientOptions.fromJson(genOptions)
        new XAiImageModelClient(api, opts, id).some
      }
      case "azure-openai" => {
        val resourceName = connection.select("resource_name").as[String]
        val deploymentId = connection.select("deployment_id").as[String]
        val apikey = connection.select("api_key").as[String]
        val api = new AzureOpenAiApi(resourceName, deploymentId, apikey, timeout.getOrElse(3.minutes), env = env)
        val opts = AzureOpenAiImageModelClientOptions.fromJson(genOptions)
        new AzureOpenAiImageModelClient(api, opts, id).some
      }
      case "luma" => {
        val api = new LumaApi(baseUrl.getOrElse(LumaApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = LumaImageModelClientOptions.fromJson(genOptions)
        new LumaImageModelClient(api, opts, id).some
      }
     case "leonardo-ai" => {
       val api = new LeonardoAIApi(baseUrl.getOrElse(LeonardoAIApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
       val opts = LeonardoAIImagesGenModelClientOptions.fromJson(genOptions)
       new LeonardoAIImageModelClient(api, opts, id).some
     }
      case "hive" => {
        val api = new HiveApi(baseUrl.getOrElse(HiveApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = HiveImageModelClientOptions.fromJson(genOptions)
        new HiveImageModelClient(api, opts, id).some
      }
      case _ => None
    }
    rawClient.map(c => ImageModelClientDecorators(this, c, env))
  }
}

object ImageModel {
  val format = new Format[ImageModel] {
    override def writes(o: ImageModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[ImageModel] = Try {
      ImageModel(
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
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "ImageModel",
      "image-models",
      "image-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ImageModel](
        format = ImageModel.format,
        clazz = classOf[ImageModel],
        keyf = id => datastores.imageModelsDataStore.key(id),
        extractIdf = c => datastores.imageModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => ImageModel(
              id = IdGenerator.namedId("image-model", env),
              name = "OpenAI gpt-image-1",
              description = "An OpenAI images generation model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OpenAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "generation" -> (
                    "model" -> "gpt-image-1"
                  ),
                  "edition" -> (
                    "model" -> "gpt-image-1"
                    )
                )
              ),
            ).json
            case Some("x-ai") => ImageModel(
              id = IdGenerator.namedId("image-model", env),
              name = "X-AI",
              description = "X-AI Images generation model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "x-ai",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> XAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "grok-2-image"
                )
              ),
            ).json
            case _ => ImageModel(
              id = IdGenerator.namedId("image-model", env),
              name = "Local images generation model",
              description = "A Local images generation model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "local-images-gen-model",
              config = Json.obj(),
            ).json
          }
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allImageModels(),
        stateOne = id => states.imageModel(id),
        stateUpdate = values => states.updateImageModels(values)
      )
    )
  }
}

trait ImageModelsDataStore extends BasicStore[ImageModel]

class KvImageModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends ImageModelsDataStore
    with RedisLikeStore[ImageModel] {
  override def fmt: Format[ImageModel]                  = ImageModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:imagemodels:$id"
  override def extractId(value: ImageModel): String    = value.id
}
