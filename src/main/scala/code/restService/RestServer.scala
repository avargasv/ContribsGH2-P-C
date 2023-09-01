package code.restService

import code.lib.AppAux._
import code.model.Entities._
import code.model.JsonFormats._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import java.time.{Duration, Instant}
import java.util.Date
import scala.util.{Failure, Success}

object RestServer {

  import akka.actor.typed.ActorSystem
  import akka.actor.typed.scaladsl.Behaviors
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route

  private def contributorsByOrganization(organization: Organization, groupLevel: String, minContribs: Int): List[Contributor] = {
    val sdf = new java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss")
    val initialInstant = Instant.now
    logger.info(s"Starting ContribsGH2-PC REST API call at ${sdf.format(Date.from(initialInstant))} - organization='$organization'")
    val repos = reposByOrganization(organization)
    // parallel retrieval of contributors by repo using Futures with cache
    val contributorsDetailed: List[Contributor] = contributorsDetailedFutureWithCache(organization, repos)
    // grouping, sorting
    val contributorsGrouped = groupContributors(organization, groupLevel, minContribs, contributorsDetailed)
    val finalInstant = Instant.now
    logger.info(s"Finished ContribsGH2-PC REST API call at ${sdf.format(Date.from(finalInstant))} - organization='$organization'")
    logger.info(f"Time elapsed from start to finish: ${Duration.between(initialInstant, finalInstant).toMillis/1000.0}%3.2f seconds")
    contributorsGrouped
  }

  def contributorsDetailedFutureWithCache(organization: Organization, repos: List[Repository]): List[Contributor] = {
    val (reposUpdatedInCache, reposNotUpdatedInCache) = repos.partition(RestServerCache.repoUpdatedInCache(organization, _))
    val contributorsDetailed_L_1: List[List[Contributor]] =
      reposUpdatedInCache.map { repo =>
        RestServerCache.retrieveContributorsFromCache(organization, repo)
      }
    val contributorsDetailed_L_F_2: List[Future[List[Contributor]]] =
      reposNotUpdatedInCache.map { repo =>
        Future {
          contributorsByRepo(organization, repo)
        }
      }
    val contributorsDetailed_F_L_2: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F_2)
    val contributorsDetailed_L_2 = Await.result(contributorsDetailed_F_L_2, timeout)
    val contributorsDetailed_L = contributorsDetailed_L_1 ++ contributorsDetailed_L_2
    // updates cache with non-existent or recently-modified repos
    contributorsDetailed_L.foreach { contribs_L =>
      if (contribs_L.length > 0) {
        val repoK = organization.trim + "-" + contribs_L.head.repo
        reposNotUpdatedInCache.find(r => (organization.trim + "-" + r.name) == repoK) match {
          case Some(repo) =>
            RestServerCache.saveContributorsToCache(organization, repo, contribs_L)
          case None =>
            ()
        }
      }
    }
    contributorsDetailed_L.flatten
  }

  def groupContributors(organization: Organization, groupLevel: String, minContribs: Int, contributorsDetailed: List[Contributor]) = {
    val (contributorsGroupedAboveMin, contributorsGroupedBelowMin) = contributorsDetailed.
      map(c => if (groupLevel == "repo") c else c.copy(repo = s"All $organization repos")).
      groupBy(c => (c.repo, c.contributor)).
      view.mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
      map(p => Contributor(p._1._1, p._1._2, p._2)).
      partition(_.contributions >= minContribs)
    val contributorsGrouped = {
      (
        contributorsGroupedAboveMin
          ++
          contributorsGroupedBelowMin.
            map(c => c.copy(contributor = "Other contributors")).
            groupBy(c => (c.repo, c.contributor)).
            view.mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
            map(p => Contributor(p._1._1, p._1._2, p._2))
        ).toList.sortWith { (c1: Contributor, c2: Contributor) =>
        if (c1.repo != c2.repo) c1.repo < c2.repo
        else if (c1.contributor == "Other contributors") false
        else if (c1.contributions != c2.contributions) c1.contributions >= c2.contributions
        else c1.contributor < c2.contributor
      }
    }
    contributorsGrouped
  }

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem(Behaviors.empty, "ContribsGH2-Server")

    val route: Route = {
      get {
        path("org" / Segment / "contributors") { organization: String =>
          parameters("group-level".optional, "min-contribs".optional) { (glo, mco) =>
            val gl = glo.getOrElse("organization")
            val mc = mco.getOrElse("0").toIntOption.getOrElse(0)
            complete(contributorsByOrganization(organization, gl, mc))
          }
        }
      }
    }

    val futureBinding = Http().newServerAt("0.0.0.0", 8080).bind(route)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        logger.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }

  }

}

import redis.embedded.RedisServer
import redis.clients.jedis.Jedis
import scala.jdk.CollectionConverters._

object RestServerCache {

  // start Redis server
  private val redisServer = new RedisServer(6379)
  try {
    redisServer.start()
  } catch {
    // just use it if already started
    case _: Throwable => ()
  }

  private val redisClient = new Jedis()
  redisClient.flushAll()

  private def contribToString(c: Contributor) = c.contributor.trim + ":" + c.contributions

  private def stringToContrib(r: Repository, s: String) = {
    val v = s.split(":").toVector
    Contributor(r.name, v(0).trim, v(1).trim.toInt)
  }

  private def buildRepoK(o:Organization, r: Repository) = o.trim + "-" + r.name

  def saveContributorsToCache(org:Organization, repo: Repository, contributors: List[Contributor]) = {
    val repoK = buildRepoK(org, repo)
    redisClient.del(repoK)
    logger.info(s"repo '$repoK' stored in cache")
    contributors.foreach { c: Contributor =>
      redisClient.lpush(repoK, contribToString(c))
    }
    redisClient.lpush(repoK, s"updatedAt:${repo.updatedAt.toString}")
  }

  def repoUpdatedInCache(org:Organization, repo: Repository): Boolean = {
    val repoK = buildRepoK(org, repo)
    redisClient.lrange(repoK, 0, 0).asScala.toList match {
      case s :: _ =>
        val cachedUpdatedAt = Instant.parse(s.substring(s.indexOf(":") + 1))
        cachedUpdatedAt.compareTo(repo.updatedAt) >= 0
      case _ => false
    }
  }

  def retrieveContributorsFromCache(org:Organization, repo: Repository) = {
    val repoK = buildRepoK(org, repo)
    val res = redisClient.lrange(repoK, 1, redisClient.llen(repoK).toInt - 1).asScala.toList
    logger.info(s"repo '$repoK' retrieved from cache, # of contributors=${res.length}")
    res.map(s => stringToContrib(repo, s))
  }

}
