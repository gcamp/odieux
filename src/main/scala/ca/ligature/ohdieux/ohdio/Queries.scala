package ca.ligature.ohdieux.ohdio

import play.api.libs.json._
import ca.ligature.ohdieux.persistence.ProgrammeType

private object Queries {
  import ApiClient._
  import RCModels._
  import RCModels.FetchResult._

  def buildGetPlaybackListByGlobalIdQuery(
      contentTypeId: Int,
      playbackListId: String
  ): Map[String, String] = {
    Map(
      "opname" -> "playbackListByGlobalId",
      "extensions" -> Json.stringify(
        JsObject(
          Seq(
            "persistedQuery" -> JsObject(
              Seq(
                "version" -> JsNumber(1),
                "sha256Hash" -> JsString(
                  "ae95ebffe69f06d85a0f287931b61e3b7bfb7485f28d4d906c376be5f830b8c0"
                )
              )
            )
          )
        )
      ),
      "variables" -> Json.stringify(
        JsObject(
          Seq(
            "params" -> JsObject(
              Seq(
                "contentTypeId" -> JsNumber(contentTypeId),
                "id" -> JsString(playbackListId)
              )
            )
          )
        )
      )
    )
  }

  def buildGetProgrammeByIdQuery(
      programmeType: ProgrammeType,
      programmeId: Int,
      pageNumber: Int
  ): Option[(Map[String, String], JsValue => JsValue)] =
    programmeType match {
      // The site now serves every non-audiobook programme through the single
      // "program" operation, whose response is adapted to the legacy model.
      case ProgrammeType.Balado | ProgrammeType.EmissionPremiere |
          ProgrammeType.GrandeSerie => {
        val extensions = JsObject(
          Seq(
            "persistedQuery" -> JsObject(
              Seq(
                "version" -> JsNumber(1),
                "sha256Hash" -> JsString(
                  "eb21dbac717c115f509e149e0dd619a678446064059d0a28d536124eb9ce88d1"
                )
              )
            )
          )
        )

        val variables = JsObject(
          Seq(
            "params" -> JsObject(
              Seq(
                "device" -> JsString("Web"),
                "id" -> JsNumber(programmeId),
                "pageNumber" -> JsNumber(pageNumber)
              )
            )
          )
        )

        return Some(
          Map(
            "opname" -> "program",
            "extensions" -> Json.stringify(extensions),
            "variables" -> Json.stringify(variables)
          ),
          programTransform
        )
      }

      case ProgrammeType.Audiobook => {
        if (pageNumber > 1) {
          return None
        }
        val extensions = JsObject(
          Seq(
            "persistedQuery" -> JsObject(
              Seq(
                "version" -> JsNumber(1),
                "sha256Hash" -> JsString(
                  "a676e346c1993bbfcf6924eac2f3e3d45ad3055721c6949b74fbd0d33a505a59"
                )
              )
            )
          )
        )

        val variables = JsObject(
          Seq(
            "params" -> JsObject(
              Seq(
                "context" -> JsString("web"),
                "id" -> JsNumber(programmeId)
              )
            )
          )
        )

        return Some(
          Map(
            "opname" -> "audioBookById",
            "extensions" -> Json.stringify(extensions),
            "variables" -> Json.stringify(variables)
          ),
          audioBookTransform
        )
      }
    }
}

private def identity[T](x: T): T = x

private given Writes[JsLookupResult] = Writes(_.getOrElse(JsNull))

// Adapts the "program" operation response to the legacy "programmeById" shape.
// Anything that is not a ProgramPage (e.g. PageError) is returned untouched
// so that the caller can report it.
private def programTransform(x: JsValue): JsValue = {
  val page = x \ "data" \ "program"
  if ((page \ "typename").asOpt[String] != Some("ProgramPage")) {
    return x
  }
  val program = page \ "program"
  val canonicalUrl = (program \ "canonicalUrl").asOpt[String].getOrElse("")
  val typename =
    if (canonicalUrl.startsWith("/balados/")) "EmissionBalado"
    else if (canonicalUrl.startsWith("/grandes-series/"))
      "EmissionGrandesSeries"
    else "EmissionPremiere"

  val items = (page \ "episodes")
    .asOpt[JsArray]
    .map(_.value.toSeq)
    .getOrElse(Seq.empty)
    .map { episode =>
      Json.obj(
        "typename" -> "ProgrammeContentDetailItem",
        "broadcastedFirstTimeAt" -> (episode \ "broadcastedFirstTimeAt"),
        "duration" -> (episode \ "duration"),
        "isBroadcastedReplay" -> (episode \ "isBroadcastedReplay"),
        "playlistItemId" -> Json.obj(
          "mediaId" -> (episode \ "mediaIds" \ 0)
            .asOpt[JsValue]
            .collect {
              case JsNumber(n) => JsString(n.toString)
              case s: JsString => s
            }
            .getOrElse(JsNull),
          "globalId2" -> Json.obj(
            "id" -> (episode \ "globalId" \ "id"),
            "contentType" -> Json.obj(
              "id" -> (episode \ "globalId" \ "contentType" \ "id")
            )
          )
        ),
        "summary" -> (episode \ "summary").asOpt[String].getOrElse(""),
        "title" -> (episode \ "title"),
        "url" -> (episode \ "url")
      )
    }

  val pageSize = (page \ "pagination" \ "pageSize").asOpt[Int].getOrElse(50)
  Json.obj(
    "data" -> Json.obj(
      "programmeById" -> Json.obj(
        "typename" -> typename,
        "canonicalUrl" -> canonicalUrl,
        "header" -> Json.obj(
          "typename" -> "ProgrammeHeader",
          "title" -> (program \ "title"),
          "summary" -> (program \ "summary").asOpt[String].getOrElse(""),
          "picture" -> Json.obj("pattern" -> (program \ "picture" \ "pattern"))
        ),
        "content" -> Json.obj(
          "typename" -> "ProgrammeContent",
          "contentDetail" -> Json.obj(
            "typename" -> "ProgrammeContentDetail",
            "items" -> items,
            "pagedConfiguration" -> Json.obj(
              "pageMaxLength" -> pageSize,
              "pageNumber" -> (page \ "pagination" \ "pageNumber"),
              "pageSize" -> pageSize,
              "totalNumberOfItems" -> (page \ "pagination" \ "totalCount")
            )
          )
        )
      )
    )
  )
}
private def audioBookTransform(x: JsValue): JsValue = {
  val patchedItems =
    (x \ "data" \ "audioBookById" \ "content" \ "contentDetail" \ "items")
      .getOrElse(Json.arr())
      .asInstanceOf[JsArray]
      .value
      .zipWithIndex
      .map(replaceAudiobookPlaylistItemId)

  // audiobooks reuse the same episode id. This hack ensures that at
  // least each segment has a relevant title. Otherwise, one random
  // segment tile will be picked as the episode title.
  Json.obj(
    "data" ->
      Json
        .obj(
          "programmeById" -> (x \ "data" \ "audioBookById")
            .getOrElse(Json.obj())
        )
        .deepMerge(
          Json.obj(
            "programmeById" -> Json.obj(
              "content" -> Json.obj(
                "contentDetail" -> Json.obj(
                  "items" -> patchedItems,
                  "pagedConfiguration" ->
                    Json.obj(
                      "pageMaxLength" -> 1,
                      "pageNumber" -> 1,
                      "pageSize" -> 25,
                      "totalNumberOfItems" -> patchedItems.length
                    )
                )
              )
            )
          )
        )
  )
}

private def replaceAudiobookPlaylistItemId(x: JsValue, index: Int): JsValue = {
  if (!x.isInstanceOf[JsObject]) {
    x
  } else {
    val playlistItemId =
      (x \ "playlistItemId" \ "globalId2" \ "id").getOrElse(JsString("unknown"))
    val inventedEpisodeId =
      -playlistItemId.as[JsString].value.toInt * 1000 - index
    x.asInstanceOf[JsObject]
      .deepMerge(
        Json.obj(
          "playlistItemId" -> Json
            .obj("globalId2" -> Json.obj("id" -> inventedEpisodeId.toString))
        )
      )
  }
}
