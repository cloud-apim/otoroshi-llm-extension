package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostModel, CostsOutput, ModalityCosts}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioTranscriptionResponseMetadataUsage, ImagesGenResponseMetadataUsage, VideosGenResponseMetadataUsage}
import play.api.libs.json.Json

// The price table bills the modalities that are not text per token when the provider counts them, and per
// image, character, second or page when it does not. These are the entries of the bundled table, so the
// units stay the ones the providers actually invoice — a call priced at zero would leave budgets untouched.
class ModalityCostsSuite extends munit.FunSuite {

  def cost(name: String, fields: (String, BigDecimal)*): CostModel =
    CostModel(name, Json.obj(fields.map { case (k, v) => (k, Json.toJsFieldJsValueWrapper(v)) }*))

  // an image model of the table: a prompt in text tokens, the pictures it reads, the picture it draws
  val gptImage = cost("gpt-image-2", "input_cost_per_token" -> BigDecimal("0.000005"), "input_cost_per_image_token" -> BigDecimal("0.000008"), "output_cost_per_image_token" -> BigDecimal("0.00003"))
  val dalle = cost("dall-e-3", "input_cost_per_image" -> BigDecimal("0.04"))
  val tts = cost("tts-1", "input_cost_per_character" -> BigDecimal("0.000015"))
  val gptTts = cost("gpt-4o-mini-tts", "input_cost_per_token" -> BigDecimal("0.0000006"), "output_cost_per_token" -> BigDecimal("0.00001"), "output_cost_per_second" -> BigDecimal("0.00025"))
  val whisper = cost("whisper-1", "input_cost_per_second" -> BigDecimal("0.0001"), "output_cost_per_second" -> BigDecimal("0.0001"))
  val transcribe = cost("gpt-4o-mini-transcribe", "input_cost_per_token" -> BigDecimal("0.00000125"), "output_cost_per_token" -> BigDecimal("0.000005"), "input_cost_per_audio_token" -> BigDecimal("0.00000125"))
  val ocr = cost("mistral-ocr-latest", "ocr_cost_per_page" -> BigDecimal("0.004"))

  // what a client builds when the provider reports no usage at all
  def noImageUsage: ImagesGenResponseMetadataUsage = ImagesGenResponseMetadataUsage(-1L, -1L, -1L, -1L, -1L)
  def noAudioUsage: AudioTranscriptionResponseMetadataUsage = AudioTranscriptionResponseMetadataUsage(-1L, -1L, -1L, Map.empty)

  def total(costs: Option[CostsOutput]): BigDecimal = costs.map(_.totalCost).getOrElse(BigDecimal(0))

  test("an image is billed on the tokens its provider counted") {
    // the call of a studio playground: 16 text tokens in, 186 tokens of picture out
    val usage = ImagesGenResponseMetadataUsage(totalTokens = 202, tokenInput = 16, tokenOutput = 186, tokenText = 16, tokenImage = 0)
    val costs = ModalityCosts.image(gptImage, usage, 1).get
    assertEquals(costs.inputCost, BigDecimal("0.000080"))
    assertEquals(costs.outputCost, BigDecimal("0.00558"))
    assertEquals(costs.totalCost, BigDecimal("0.005660"))
    // an input the provider does not split is text, and the pictures it reads are billed at their own price
    val mixed = ImagesGenResponseMetadataUsage(totalTokens = 30, tokenInput = 20, tokenOutput = 10, tokenText = -1, tokenImage = 5)
    val split = ModalityCosts.image(gptImage, mixed, 1).get
    assertEquals(split.inputCost, BigDecimal(15) * BigDecimal("0.000005") + BigDecimal(5) * BigDecimal("0.000008"))
  }

  test("an image model that counts nothing is billed by the image") {
    assertEquals(total(ModalityCosts.image(dalle, noImageUsage, 1)), BigDecimal("0.04"))
    assertEquals(total(ModalityCosts.image(dalle, noImageUsage, 3)), BigDecimal("0.12"))
    // and a model the table prices in no unit at all is not billed
    assertEquals(ModalityCosts.image(cost("unknown-image"), noImageUsage, 1), None)
  }

  test("a voice is billed on the characters it reads") {
    assertEquals(total(ModalityCosts.speech(tts, 200L, None)), BigDecimal("0.003000"))
    // a voice billed per second of audio: the gateway never measures them, so it says so instead of billing zero
    assertEquals(ModalityCosts.speech(gptTts, 200L, None), None)
    assertEquals(total(ModalityCosts.speech(gptTts, 200L, Some(BigDecimal(8)))), BigDecimal("0.00200"))
  }

  test("a transcription is billed on its tokens, or on the seconds of audio it read") {
    val usage = AudioTranscriptionResponseMetadataUsage(input = 25, output = 10, total = 35, input_details = Map("audio_tokens" -> 20L))
    val costs = ModalityCosts.transcription(transcribe, usage, None).get
    assertEquals(costs.inputCost, BigDecimal(25) * BigDecimal("0.00000125"))
    assertEquals(costs.outputCost, BigDecimal(10) * BigDecimal("0.000005"))
    // whisper reports no token: only the length of the audio can bill it
    assertEquals(ModalityCosts.transcription(whisper, noAudioUsage, None), None)
    assertEquals(total(ModalityCosts.transcription(whisper, noAudioUsage, Some(BigDecimal(12)))), BigDecimal("0.0012"))
  }

  test("an extraction is billed by the page") {
    assertEquals(total(ModalityCosts.ocr(ocr, 3L)), BigDecimal("0.012"))
    assertEquals(ModalityCosts.ocr(ocr, 0L), None)
    assertEquals(ModalityCosts.ocr(cost("free-ocr"), 3L), None)
  }

  test("a video is billed on its tokens, or on its seconds") {
    val priced = cost("veo-3", "output_cost_per_video_per_second" -> BigDecimal("0.4"))
    assertEquals(total(ModalityCosts.video(priced, VideosGenResponseMetadataUsage(-1L, -1L, -1L, -1L, -1L), Some(BigDecimal(8)))), BigDecimal("3.2"))
  }

  test("a model is billable when the table holds a unit the gateway can measure, and only then") {
    val image = Seq("image")
    val audio = Seq("audio")
    assert(ModalityCosts.canBill(gptImage, image, Seq("images_generations")), "an image model priced per token is billable")
    assert(ModalityCosts.canBill(dalle, image, Seq("images_generations")), "an image model priced per image is billable")
    assert(!ModalityCosts.canBill(cost("unknown-image"), image, Seq("images_generations")), "a model the table does not price is not billable")
    assert(ModalityCosts.canBill(tts, audio, Seq("audio_speech")), "a voice priced per character is billable")
    // the one the studio shows a price for, and that nothing can bill: it is priced per second of audio
    assert(!ModalityCosts.canBill(gptTts, audio, Seq("audio_speech")), "a voice priced per second of audio is not billable")
    assert(ModalityCosts.canBill(transcribe, audio, Seq("audio_transcriptions")), "a transcription priced per token is billable")
    assert(!ModalityCosts.canBill(whisper, audio, Seq("audio_transcriptions")), "a transcription priced per second is not billable")
    assert(ModalityCosts.canBill(ocr, Seq("ocr"), Seq("ocr")), "an ocr model priced per page is billable")
    assert(!ModalityCosts.canBill(gptImage, Seq("text"), Seq("chat_completions")), "text is billed by the chat decorator, not here")
  }
}
