package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiBudgetActionOnExceedMode
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results

import scala.concurrent._
import scala.util._

case class EnforceAiBudgetsConfig(budgets: Seq[String], consumerMustHaveBudget: Boolean) extends NgPluginConfig {
  def json: JsValue = EnforceAiBudgetsConfig.format.writes(this)
}

object EnforceAiBudgetsConfig {

  val format = new Format[EnforceAiBudgetsConfig] {
    override def writes(o: EnforceAiBudgetsConfig): JsValue = Json.obj(
      "budgets" -> o.budgets,
      "consumer_must_have_budget" -> o.consumerMustHaveBudget
    )
    override def reads(json: JsValue): JsResult[EnforceAiBudgetsConfig] = Try {
      EnforceAiBudgetsConfig(
        budgets = (json \ "budgets").asOpt[Seq[String]].getOrElse(Seq.empty),
        consumerMustHaveBudget = (json \ "consumer_must_have_budget").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  
  val configFlow = Seq(
    "budgets",
    "consumer_must_have_budget"
  )

  val configSchema = Some(Json.obj(
    "budgets" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Budgets",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/ai-budgets",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "consumer_must_have_budget" -> Json.obj(
      "type" -> "boolean",
      "label" -> s"Consumer must have budget",
    )
  ))

  val default = EnforceAiBudgetsConfig(Seq.empty, true)
}

class EnforceAiBudgets extends NgAccessValidator {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.AccessControl)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = EnforceAiBudgetsConfig.configFlow
  override def configSchema: Option[JsObject] = EnforceAiBudgetsConfig.configSchema
  override def name: String = "Cloud APIM - Enforce AI Budget"
  override def description: Option[String] = """This plugin block calls with no more budgets""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = EnforceAiBudgetsConfig.default.some

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Enforce AI Budget' plugin is available !")
    }
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(EnforceAiBudgetsConfig.format).getOrElse(EnforceAiBudgetsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    val fbugets = if (config.budgets.nonEmpty) {
      config.budgets.flatMap(b => ext.states.budget(b)).vfuture
    } else {
      ext.datastores.budgetsDataStore.findMatchingBudgets(ctx.json, ctx.apikey, ctx.user)
    }
    fbugets.flatMap { budgets =>
      budgets.filterAsync(_.isNotWithinBudget()).flatMap { budgets =>
        if (budgets.nonEmpty) {
          if (budgets.head.actionOnExceed.mode == AiBudgetActionOnExceedMode.Soft) {
            // TODO: alert ???
            NgAccess.NgAllowed.vfuture
          } else {
            Errors
              .craftResponseResult(
                "Exceeded budgets",
                Results.TooManyRequests,
                ctx.request,
                None,
                None,
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        } else {
          if (config.consumerMustHaveBudget) {
            Errors
              .craftResponseResult(
                "Consumer must have a budget",
                Results.NotFound,
                ctx.request,
                None,
                None,
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          } else {
            NgAccess.NgAllowed.vfuture
          }
        }
      }
    }
  }
}
