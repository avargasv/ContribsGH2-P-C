package code.model

object Entities {

  // entities of the REST service

  import java.time.Instant

  type Organization = String

  case class Repository(name: String, updatedAt: Instant)

  case class Contributor(repo: String, contributor: String, contributions: Int)

  // auxiliary types for the REST client

  type BodyString = String
  type ErrorString = String

}

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

object JsonFormats extends SprayJsonSupport with DefaultJsonProtocol {
  import Entities.Contributor

  implicit val contributorJsonFormat = jsonFormat3(Contributor)
}
