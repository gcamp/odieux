package ca.ligature.ohdieux.services.manifest

import javax.inject.Inject
import ca.ligature.ohdieux.persistence.ManifestRepository
import ca.ligature.ohdieux.persistence.ProgrammeManifestView

import ca.ligature.ohdieux.services.manifest.types._
import ca.ligature.ohdieux.services.manifest.render.ManifestRenderer
import org.apache.pekko.actor.typed.ActorRef
import ca.ligature.ohdieux.actors.scraper.programme.ProgrammeScraperActor

case class ManifestService @Inject() (
    val manifestRepository: ManifestRepository,
    val scraperActor: ActorRef[ProgrammeScraperActor.Message],
    val serverOptions: ManifestRenderServerOptions
) {

  def renderManifest(
      programmeId: Int,
      userOptions: ManifestRenderUserOptions
  ): Option[RenderedProgrammeManifest] = {
    val programme = manifestRepository
      .getProgrammeManifest(programmeId)

    val renderer = ManifestRenderer(userOptions, serverOptions)

    if (serverOptions.autoAddProgrammes && programme.isEmpty) {
      scraperActor ! ProgrammeScraperActor.Message.FetchProgramme(programmeId)
    }

    programme
      .map(renderer.render)

  }

}
