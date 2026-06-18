package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.Catalog
import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpSupport
import com.cloud.apim.otoroshi.extensions.aigateway.mcp.{OpenApiMcpClient, OpenApiSchema}
import play.api.libs.json._

/**
 * Pure unit tests (no Otoroshi server) for the OpenAPI MCP connector building blocks:
 *  - the JSON-Schema -> langchain4j JsonSchemaElement converter (round-tripped via McpSupport.schemaToJson)
 *  - the reuse of Catalog.parse on a generic spec
 *  - the tool-name slugification
 */
class OpenApiMcpClientSuite extends munit.FunSuite {

  private val petSpec: JsObject = Json.obj(
    "openapi" -> "3.0.0",
    "info" -> Json.obj("title" -> "Petstore", "version" -> "1.0.0"),
    "servers" -> Json.arr(Json.obj("url" -> "https://api.example.com")),
    "paths" -> Json.obj(
      "/pets/{petId}" -> Json.obj(
        "get" -> Json.obj(
          "operationId" -> "getPetById",
          "summary" -> "Get a pet by id",
          "tags" -> Json.arr("pets"),
          "parameters" -> Json.arr(
            Json.obj("name" -> "petId", "in" -> "path", "required" -> true, "schema" -> Json.obj("type" -> "string"))
          )
        )
      ),
      "/pets" -> Json.obj(
        "post" -> Json.obj(
          "operationId" -> "createPet",
          "summary" -> "Create a pet",
          "tags" -> Json.arr("pets", "admin"),
          "requestBody" -> Json.obj(
            "required" -> true,
            "content" -> Json.obj("application/json" -> Json.obj("schema" -> Json.obj("$ref" -> "#/components/schemas/Pet")))
          )
        )
      )
    ),
    "components" -> Json.obj(
      "schemas" -> Json.obj(
        "Pet" -> Json.obj(
          "type" -> "object",
          "required" -> Json.arr("id", "name"),
          "properties" -> Json.obj(
            "id" -> Json.obj("type" -> "integer"),
            "name" -> Json.obj("type" -> "string"),
            "status" -> Json.obj("type" -> "string", "enum" -> Json.arr("available", "pending", "sold")),
            "tags" -> Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string"))
          )
        )
      )
    )
  )

  private lazy val doc: Catalog.Document = Catalog.parse(petSpec, dropBulk = false)

  test("Catalog.parse reads a generic OpenAPI spec") {
    assertEquals(doc.operations.size, 2)
    val ids = doc.operations.map(_.operationId).toSet
    assert(ids.contains("getPetById"), "should expose getPetById")
    assert(ids.contains("createPet"), "should expose createPet")
    val get = doc.byOperationId("getPetById")
    assertEquals(get.method, "get")
    assertEquals(get.path, "/pets/{petId}")
    assertEquals(get.parameters.head.in, "path")
    assert(get.parameters.head.required)
    assert(doc.byOperationId("createPet").requestBody.isDefined)
  }

  test("schema converter: primitives round-trip via schemaToJson") {
    val strBack = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(Json.obj("type" -> "string", "description" -> "a name"), doc))
    assertEquals((strBack \ "type").as[String], "string")
    assertEquals((strBack \ "description").as[String], "a name")

    val intBack = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(Json.obj("type" -> "integer"), doc))
    assertEquals((intBack \ "type").as[String], "integer")

    val boolBack = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(Json.obj("type" -> "boolean"), doc))
    assertEquals((boolBack \ "type").as[String], "boolean")
  }

  test("schema converter: enum becomes a string schema with enum values") {
    val back = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(
      Json.obj("type" -> "string", "enum" -> Json.arr("a", "b", "c")), doc))
    assertEquals((back \ "type").as[String], "string")
    assertEquals((back \ "enum").as[Seq[String]], Seq("a", "b", "c"))
  }

  test("schema converter: array of string") {
    val back = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(
      Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string")), doc))
    assertEquals((back \ "type").as[String], "array")
    assertEquals((back \ "items" \ "type").as[String], "string")
  }

  test("schema converter: $ref is resolved eagerly against the spec components") {
    val back = McpSupport.schemaToJson(OpenApiSchema.toJsonSchemaElement(
      Json.obj("$ref" -> "#/components/schemas/Pet"), doc))
    assertEquals((back \ "type").as[String], "object")
    assertEquals((back \ "properties" \ "id" \ "type").as[String], "integer")
    assertEquals((back \ "properties" \ "name" \ "type").as[String], "string")
    // nested enum + array survive the conversion
    assertEquals((back \ "properties" \ "status" \ "enum").as[Seq[String]], Seq("available", "pending", "sold"))
    assertEquals((back \ "properties" \ "tags" \ "type").as[String], "array")
    assertEquals((back \ "required").as[Seq[String]].toSet, Set("id", "name"))
  }

  test("tool name slugification keeps valid chars and dedups") {
    assertEquals(OpenApiMcpClient.slugifyName("getPetById"), "getPetById")
    assertEquals(OpenApiMcpClient.slugifyName("get pet/by id"), "get_pet_by_id")
    assertEquals(OpenApiMcpClient.slugifyName("  weird@@name!! "), "weird_name")
  }
}
