package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.github.tjake.jlama.model.ModelSupport
import com.github.tjake.jlama.safetensors.DType
import com.github.tjake.jlama.safetensors.prompt.PromptContext
import com.github.tjake.jlama.util.Downloader

import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.function.BiConsumer

class JlamaSuite extends munit.FunSuite {

  test("use jlama") {

    val model = "tjake/Llama-3.2-1B-Instruct-JQ4"
    val workingDirectory = "./jlama-models"
    val prompt = "What is the best season to plant avocados?"
    val localModelPath = new Downloader(workingDirectory, model).huggingFaceModel()
    val m = ModelSupport.loadModel(localModelPath, DType.F32, DType.I8)

    val ctx = if (m.promptSupport().isPresent()) {
      println("promptSupport")
      m.promptSupport()
        .get()
        .builder()
        .addSystemMessage("You are a helpful chatbot who writes short responses.")
        .addUserMessage(prompt)
        .build()
    } else {
      PromptContext.of(prompt)
    }
    println(s"Prompt: ${ctx.getPrompt()}\n")
    for (i <- 0 to 10) {
      val start = System.currentTimeMillis()
      println("=========================================================")
      println(s"[$i] Start")
      val r = m.generate(UUID.randomUUID(), ctx, 0.8f, 500, new BiConsumer[java.lang.String, java.lang.Float] {
        override def accept(t: java.lang.String, u: java.lang.Float): Unit = {
          println(s"on: ${t} - ${u}")
        }
      })
      val time = System.currentTimeMillis() - start
      println("=========================================================")
      println(s"duration: ${time} ms.")
      println("=========================================================")
      println(r.responseText);
      println("=========================================================")
      println("\n\n")
    }
  }
}
