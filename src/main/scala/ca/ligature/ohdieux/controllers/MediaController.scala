package ca.ligature.ohdieux.controllers

import javax.inject.Inject
import play.api.mvc.{
  AnyContent,
  BaseController,
  ControllerComponents,
  RangeResult,
  Request,
  ResponseHeader,
  Result
}
import ca.ligature.ohdieux.actors.file.impl.ArchivedFileRepository
import ca.ligature.ohdieux.persistence.MediaRepository
import play.api.Configuration

import java.io.File
import scala.concurrent.ExecutionContext
import java.nio.file.Files
import org.apache.pekko.stream.scaladsl.{FileIO, StreamConverters}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.http.HttpEntity

class MediaController @Inject() (
    archive: ArchivedFileRepository,
    mediaRepository: MediaRepository,
    configuration: Configuration,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController {

  private val userAgent: String = configuration.get[String]("rc.user_agent")
  private val httpClient = java.net.http.HttpClient.newBuilder()
    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
    .build()

  def getImage(programme_id: Int) = Action {
    val imageHandle = archive.createImageHandle(programme_id)
    if (!archive.exists(imageHandle)) {
      NotFound("")
    } else {
      Ok.sendFile(
        content = new File(archive.getPath(imageHandle).toString),
        fileName = _ => None
      ).as("image/jpeg")
    }
  }

  def getMedia(media_id: Int) = Action {
    implicit request: Request[AnyContent] =>
      val mediaHandle = archive.createMediaHandle(media_id)
      if (archive.exists(mediaHandle)) {
        val path = archive.getPath(mediaHandle)
        val source: Source[ByteString, ?] = FileIO.fromPath(path)
        val contentLength = Some(Files.size(path))

        RangeResult.ofSource(
          entityLength = contentLength,
          source = source,
          rangeHeader = request.headers.get(RANGE),
          fileName = Some(s"${media_id}.m4a"),
          contentType = Some("audio/mpeg")
        )
      } else {
        mediaRepository.getById(media_id) match {
          case Some(media) =>
            val httpRequest = java.net.http.HttpRequest
              .newBuilder()
              .uri(java.net.URI.create(media.upstream_url))
              .header("User-Agent", userAgent)
              .build()
            val response = httpClient.send(
              httpRequest,
              java.net.http.HttpResponse.BodyHandlers.ofInputStream()
            )
            val source = StreamConverters.fromInputStream(() => response.body())
            val contentLength = response.headers().firstValueAsLong("Content-Length")
            Ok.streamed(source, contentLength = if (contentLength.isPresent) Some(contentLength.getAsLong) else None, contentType = Some("audio/mpeg"))
          case None =>
            NotFound("unknown media")
        }
      }
  }
  def getMediaFile(media_file: String) = {
    val media_id = ".m4a$".r.replaceAllIn(media_file, "").toInt
    getMedia(media_id)
  }
}
