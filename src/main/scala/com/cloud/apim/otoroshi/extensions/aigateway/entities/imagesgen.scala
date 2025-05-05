package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.ImagesGenModelClient
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

case class ImagesGenModel(
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
  override def json: JsValue                    = ImagesGenModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getImagesGenModelClient()(implicit env: Env): Option[ImagesGenModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    // val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(OpenAiApi.baseUrl, token, timeout.getOrElse(30.seconds), providerName = "OpenAI", env = env)
        val opts = OpenAiImagesGenModelClientOptions.fromJson(options)
        new OpenAiImagesGenModelClient(api, opts, id).some
      }
      case "x-ai" => {
        val api = new XAiApi(XAiApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
        val opts = XAiImagesGenModelClientOptions.fromJson(options)
        new XAiImagesGenModelClient(api, opts, id).some
      }
      case "azure-openai" => {
        val resourceName = connection.select("resource_name").as[String]
        val deploymentId = connection.select("deployment_id").as[String]
        val apikey = connection.select("api_key").as[String]
        val api = new AzureOpenAiApi(resourceName, deploymentId, apikey, timeout.getOrElse(10.seconds), env = env)
        val opts = AzureOpenAiImagesGenModelClientOptions.fromJson(options)
        new AzureOpenAiImagesGenModelClient(api, opts, id).some
      }
      case "luma" => {
        val api = new LumaApi(LumaApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
        val opts = LumaImagesGenModelClientOptions.fromJson(options)
        new LumaImagesGenModelClient(api, opts, id).some
      }
//      case "leonardo-ai" => {
//        val api = new LeonardoAIApi(LeonardoAIApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
//        val opts = LeonardoAIImagesGenModelClientOptions.fromJson(options)
//        new LeonardoAIImagesGenModelClient(api, opts, id).some
//      }
      case "hive" => {
        val api = new HiveApi(HiveApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
        val opts = HiveImagesGenModelClientOptions.fromJson(options)
        new HiveImagesGenModelClient(api, opts, id).some
      }
      case _ => None
    }
  }
}

object ImagesGenModel {
  val format = new Format[ImagesGenModel] {
    override def writes(o: ImagesGenModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[ImagesGenModel] = Try {
      ImagesGenModel(
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
      "ImagesGenModel",
      "images-gen",
      "images-gen",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ImagesGenModel](
        format = ImagesGenModel.format,
        clazz = classOf[ImagesGenModel],
        keyf = id => datastores.imagesGenModelsDataStore.key(id),
        extractIdf = c => datastores.imagesGenModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => ImagesGenModel(
              id = IdGenerator.namedId("provider", env),
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
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "model" -> "gpt-image-1"
                )
              ),
            ).json
            case Some("x-ai") => ImagesGenModel(
              id = IdGenerator.namedId("provider", env),
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
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "model" -> "grok-2-image"
                )
              ),
            ).json
            case _ => ImagesGenModel(
              id = IdGenerator.namedId("provider", env),
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
        stateAll = () => states.allImgGensModels(),
        stateOne = id => states.imgGensModels(id),
        stateUpdate = values => states.updateImgGensModels(values)
      )
    )
  }
}

trait ImagesGenModelsDataStore extends BasicStore[ImagesGenModel]

class KvImagesGenModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends ImagesGenModelsDataStore
    with RedisLikeStore[ImagesGenModel] {
  override def fmt: Format[ImagesGenModel]                  = ImagesGenModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:imgensmds:$id"
  override def extractId(value: ImagesGenModel): String    = value.id
}
