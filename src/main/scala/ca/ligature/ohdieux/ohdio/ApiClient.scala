package ca.ligature.ohdieux.ohdio

import scala.concurrent.Future
import sttp.client4.quick.*
import sttp.client4.Response
import play.api.libs.json._
import ca.ligature.ohdieux.persistence.ProgrammeType
import ca.ligature.ohdieux.utils.|>
import play.api.Logger
import scala.util.control.NonFatal

case class ApiClient(val baseUrl: String, val userAgent: String) {
  import ApiClient._
  import RCModels._
  import RCModels.FetchResult._

  val logger = Logger(this.getClass.getName)

  logger.info(
    s"Initialized ApiClient to ${baseUrl} with User-Agent \"${userAgent}\""
  )

  def getProgrammeById(
      programmeType: ProgrammeType,
      programmeId: Int,
      pageNumber: Int
  ): FetchResult[ProgrammeById] = {
    logger.info(
      s"getProgrammeById(${programmeType}/${programmeId},${pageNumber})"
    )
    if (pageNumber < 1) {
      return FetchFailure("Cannot lookup page below 1.")
    }
    val queryBuilder =
      Queries.buildGetProgrammeByIdQuery(programmeType, programmeId, pageNumber)

    if (queryBuilder.isEmpty) {
      return FetchFailure("No query to send")
    }

    val (queryParams, transform) = queryBuilder.get
    val response =
      try {
        send(uri"$baseUrl/bff/audio/graphql?$queryParams")
      } catch {
        case NonFatal(error) =>
          return FetchFailure(
            s"Request failed: ${Option(error.getMessage()).getOrElse(error.toString)}"
          )
      }

    if (!response.code.isSuccess) {
      return FetchFailure(s"Request failed with ${response.code.code}")
    }

    val body =
      try {
        Json.parse(response.body) |> transform
      } catch {
        case NonFatal(error) =>
          return ParseFailure(
            s"Could not parse programme response: ${Option(error.getMessage()).getOrElse(error.toString)}"
          )
      }

    val decoded =
      (body \ "data" \ "programmeById").validate[ProgrammeById]

    decoded
      .map(Success(_))
      .getOrElse {
        val reason = describeFailure(body)
        logger.error(
          s"Could not decode programme ${programmeType}/${programmeId} page ${pageNumber}: $reason"
        )
        ParseFailure(reason)
      }
  }

  private def describeFailure(body: JsValue): String = {
    val graphqlError = (body \ "errors" \ 0 \ "message").asOpt[String]
    val pageError = (body \ "data" \ "program" \ "typename")
      .asOpt[String]
      .filter(_ == "PageError")
      .map(_ => "Programme not found (PageError)")
    graphqlError
      .map(m => s"API error: $m")
      .orElse(pageError)
      .getOrElse("Unexpected programme response format")
  }

  def getPlaybacklistById(
      contentTypeId: Int,
      playbackListId: String
  ): FetchResult[PlaybackListById] = {
    logger.info(s"getPlaybackListById(${contentTypeId}, ${playbackListId})")
    if (playbackListId.toInt < 0) {
      return FetchFailure(s"Refusing getPlaybackListById with negative id.")
    }
    val query =
      Queries.buildGetPlaybackListByGlobalIdQuery(contentTypeId, playbackListId)

    val response = send(uri"$baseUrl/bff/audio/graphql?$query")

    if (!response.code.isSuccess) {
      return FetchFailure(s"Request failed with ${response.code.code}")
    }

    val body = Json.parse(response.body)

    val decoded =
      (body \ "data" \ "playbackListByGlobalId").validate[PlaybackListById]

    decoded.map(Success(_)).getOrElse(ParseFailure(_1))
  }

  def getMedia(
      mediaId: Int,
      tech: "hls" | "progressive"
  ): FetchResult[MediaStream] = {
    logger.info(s"getMedia(${mediaId}, ${tech})")
    val query = Map(
      "appCode" -> "medianet",
      "connectionType" -> "hd",
      "deviceType" -> "ipad",
      "idMedia" -> mediaId,
      "multibitrate" -> "true",
      "output" -> "json",
      "tech" -> tech
    )

    val response = send(uri"$baseUrl/media/validation/v2?$query")

    if (!response.code.isSuccess) {
      return FetchFailure(s"Request failed with ${response.code.code}")
    }

    val body = Json.parse(response.body)

    val decoded =
      (body).validate[MediaStream]

    decoded.map(Success(_)).getOrElse(ParseFailure(_1))
  }

  // Radio-Canada answers bursts with 429 for every request, so space them out and back off.
  private var lastRequestAt = 0L

  private def send(url: sttp.model.Uri): Response[String] = synchronized {
    sendWithRetry(url, 0)
  }

  @scala.annotation.tailrec
  private def sendWithRetry(url: sttp.model.Uri, attempt: Int): Response[String] = {
    val wait = lastRequestAt + MinRequestIntervalMs - System.currentTimeMillis()
    if (wait > 0) Thread.sleep(wait)
    val response = quickRequest
      .get(url)
      .header("User-Agent", userAgent)
      .header("Content-Type", "application/json")
      .send()
    lastRequestAt = System.currentTimeMillis()

    if (response.code.code != 429 || attempt >= RateLimitBackoffsMs.length) {
      return response
    }
    val backoff = response
      .header("Retry-After")
      .flatMap(_.toLongOption)
      .map(_ * 1000)
      .getOrElse(RateLimitBackoffsMs(attempt))
    logger.warn(s"Rate limited by Radio-Canada, retrying in ${backoff / 1000}s")
    Thread.sleep(backoff)
    sendWithRetry(url, attempt + 1)
  }

}

object ApiClient {
  private val MinRequestIntervalMs = 1000L
  private val RateLimitBackoffsMs = Seq(30_000L, 120_000L, 300_000L)
}
