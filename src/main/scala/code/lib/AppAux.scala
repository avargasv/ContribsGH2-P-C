package code.lib

import org.slf4j.LoggerFactory
import scala.concurrent.duration._

object AppAux {

  val logger = LoggerFactory.getLogger("ContribsGH2-PC.log")
  logger.info("Logger created")

  val timeout = 15.seconds

  val gh_token_S = System.getenv("GH_TOKEN")
  val gh_token =
    if (gh_token_S != null) {
      AppAux.logger.info("OAUTH token set from GH_TOKEN environment variable")
      gh_token_S
    } else {
      AppAux.logger.info("No GH_TOKEN environment variable found")
      null
    }

}
