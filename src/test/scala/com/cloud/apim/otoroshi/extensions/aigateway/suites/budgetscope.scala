package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudget, ApikeyOwner}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation, PrivateAppsUser}
import play.api.libs.json.*

/** Which calls a budget scoped to users counts. No gateway needed: the scope is matched on the caller alone. */
class BudgetScopeSuite extends munit.FunSuite {

  // selectors never read the environment, only rules do
  private given Env = null

  private def budget(users: Seq[String], apikeys: Seq[String] = Seq.empty): AiBudget = AiBudget.format.reads(Json.obj(
    "id"               -> "budget_1",
    "name"             -> "budget",
    "description"      -> "",
    "enabled"          -> true,
    "duration"         -> Json.obj("value" -> 30, "unit" -> "day"),
    "limits"           -> Json.obj("total_usd" -> 10),
    "scope"            -> Json.obj("users" -> users, "apikeys" -> apikeys, "always_apply_rules" -> true),
    "action_on_exceed" -> Json.obj("mode" -> "block")
  )).get

  private def key(owner: Option[String]): ApiKey =
    ApiKey(clientId = "key_1", clientSecret = "secret", clientName = "app", authorizedEntities = Seq.empty,
      metadata = owner.map(o => Map(ApikeyOwner.MetadataKey -> o)).getOrElse(Map.empty))

  private def user(email: String): PrivateAppsUser =
    PrivateAppsUser(randomId = s"ai-studio-$email", name = email, email = email, profile = Json.obj(), realm = "ai-studio",
      authConfigId = "ai-studio", otoroshiData = None, tags = Seq.empty, metadata = Map.empty, location = EntityLocation())

  private def counts(b: AiBudget, apikey: Option[ApiKey], u: Option[PrivateAppsUser]): Boolean =
    b.matches(Json.obj(), apikey, u, None, None)

  test("a budget of a user counts their chats and the calls of the api keys they own") {
    val jane = budget(Seq("jane@acme.io"))
    assert(counts(jane, None, Some(user("jane@acme.io"))), "jane chatting")
    assert(counts(jane, Some(key(Some("jane@acme.io"))), None), "an app with a key owned by jane")
    assert(counts(budget(Seq(".*@acme.io")), Some(key(Some("jane@acme.io"))), None), "owners are matched like users, regexes included")
  }

  test("a budget of a user ignores the keys of others, workspace keys and the owner when someone else is calling") {
    val jane = budget(Seq("jane@acme.io"))
    assert(!counts(jane, Some(key(Some("john@acme.io"))), None), "a key owned by john")
    assert(!counts(jane, Some(key(None)), None), "a workspace key")
    assert(!counts(jane, Some(key(Some("jane@acme.io"))), Some(user("john@acme.io"))), "john calling with jane's key counts for john")
    assert(!counts(budget(Seq.empty, Seq("key_2")), Some(key(Some("jane@acme.io"))), None), "a budget without users")
  }
}
