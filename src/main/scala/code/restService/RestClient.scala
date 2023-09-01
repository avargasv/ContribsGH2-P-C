package code.restService

import code.lib.AppAux._
import code.model.Entities._

object RestClient {

  import java.time.Instant

  def reposByOrganization(organization: Organization): List[Repository] = {
    val resp = processResponseBody(s"https://api.github.com/orgs/$organization/repos") { responsePage =>
      val full_name_RE = s""","full_name":"$organization/([^"]+)",""".r
      val full_name_I = for (full_name_RE(full_name) <- full_name_RE.findAllIn(responsePage)) yield full_name
      val updated_at_RE = s""","updated_at":"([^"]+)",""".r
      val updated_at_I = for (updated_at_RE(updated_at) <- updated_at_RE.findAllIn(responsePage)) yield updated_at
      full_name_I.zip(updated_at_I).map(p => Repository(p._1, Instant.parse(p._2))).toList
    }
    logger.info(s"# of repos=${resp.length}")
    resp
  }

  def contributorsByRepo(organization: Organization, repo: Repository): List[Contributor] = {
    val resp = processResponseBody(s"https://api.github.com/repos/$organization/${repo.name}/contributors") { responsePage =>
      val login_RE = """"login":"([^"]+)"""".r
      val login_I = for (login_RE(login) <- login_RE.findAllIn(responsePage)) yield login
      val contributions_RE = """"contributions":([0-9]+)""".r
      val contributions_I = for (contributions_RE(contributions) <- contributions_RE.findAllIn(responsePage)) yield contributions
      val contributors_L = login_I.zip(contributions_I).map(p => Contributor(repo.name, p._1, p._2.toInt)).toList
      contributors_L
    }
    logger.info(s"repo='${repo.name}', # of contributors=${resp.length}")
    resp
  }

  import scala.annotation.tailrec

  private def processResponseBody[T](url: String) (processPage: BodyString => List[T]): List[T] = {

    @tailrec
    def processResponsePage(processedPages: List[T], pageNumber: Int): List[T] = {
      val eitherPageBody = getResponseBody(s"$url?page=$pageNumber&per_page=100")
      eitherPageBody match {
        case Right(pageBody) if pageBody.length > 2 =>
          val processedPage = processPage(pageBody)
          processResponsePage(processedPages ++ processedPage, pageNumber + 1)
        case Right(_) =>
          processedPages
        case Left(error) =>
          logger.info(s"processResponseBody error - $error")
          processedPages
      }
    }

    processResponsePage(List.empty[T], 1)
  }

  import akka.actor.typed.ActorSystem
  import akka.actor.typed.scaladsl.Behaviors
  import akka.http.scaladsl.model.headers.RawHeader
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import scala.concurrent.Await

  implicit val system = ActorSystem(Behaviors.empty, "ContribsGH2-Client")
  implicit val executionContext = system.executionContext

  def getResponseBody(url: String): Either[BodyString, ErrorString] = {

    val request =
      if (gh_token != null) HttpRequest(HttpMethods.GET, url).withHeaders(RawHeader("Authorization", gh_token))
      else HttpRequest(HttpMethods.GET, url)

    val response = Await.result(Http().singleRequest(request), timeout)

    response.status match {
      case StatusCodes.OK =>
        val body = Await.result(response.entity.toStrict(timeout).map { strictEntity => strictEntity.data.utf8String }, timeout)
        Right(body)
      case StatusCodes.Forbidden =>
        Left("API rate limit exceeded")
      case StatusCodes.NotFound =>
        Left("Non-existent organization")
      case _ =>
        Left(s"Unexpected StatusCode ${response.status.intValue}")
    }
  }

}
