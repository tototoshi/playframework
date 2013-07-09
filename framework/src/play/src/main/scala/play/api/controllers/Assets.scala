package controllers

import play.api._
import play.api.mvc._
import play.api.libs._
import play.api.libs.iteratee._

import Play.current

import java.io._
import java.net.JarURLConnection
import scalax.io.{ Resource }
import org.joda.time.format.{ DateTimeFormatter, DateTimeFormat }
import org.joda.time.DateTimeZone
import collection.JavaConverters._
import scala.util.control.NonFatal

/**
 * Controller that serves static resources.
 *
 * Resources are searched in the classpath.
 *
 * It handles Last-Modified and ETag header automatically.
 * If a gzipped version of a resource is found (Same resource name with the .gz suffix), it is served instead.
 *
 * You can set a custom Cache directive for a particular resource if needed. For example in your application.conf file:
 *
 * {{{
 * "assets.cache./public/images/logo.png" = "max-age=3600"
 * }}}
 *
 * You can use this controller in any application, just by declaring the appropriate route. For example:
 * {{{
 * GET     /assets/\uFEFF*file               controllers.Assets.at(path="/public", file)
 * }}}
 */
object Assets extends AssetsBuilder

class AssetsBuilder extends Controller {

  private val timeZoneCode = "GMT"

  //Dateformatter is immutable and threadsafe
  private val df: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss '" + timeZoneCode + "'").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  //Dateformatter is immutable and threadsafe
  private val dfp: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  private val parsableTimezoneCode = " " + timeZoneCode

  private lazy val defaultCharSet = Play.configuration.getString("default.charset").getOrElse("utf-8")

  private def addCharsetIfNeeded(mimeType: String): String =
    if (MimeTypes.isText(mimeType))
      "; charset=" + defaultCharSet
    else ""

  /**
   * Generates an `Action` that serves a static resource.
   *
   * @param path the root folder for searching the static resource files, such as `"/public"`
   * @param file the file part extracted from the URL
   */
  def at(path: String, file: String): Action[AnyContent] = Action { request =>

    def parseDate(date: String): Option[java.util.Date] = try {
      //jodatime does not parse timezones, so we handle that manually
      val d = dfp.parseDateTime(date.replace(parsableTimezoneCode, "")).toDate
      Some(d)
    } catch {
      case NonFatal(_) => None
    }

    def ensurePrefixedWithSlash(s: String): String = if (! s.startsWith("/")) "/" + s else s

    def resourceFileExists(path: String, resourceName: String): Boolean = {
      (! new File(resourceName).isDirectory) && new File(resourceName).getCanonicalPath.startsWith(new File(path).getCanonicalPath)
    }

    val resourceName = ensurePrefixedWithSlash(path + "/" + file)

    if (! resourceFileExists(path, resourceName)) {
      NotFound
    } else {

      def acceptEncodings(request: RequestHeader): Seq[String] = for {
        acceptEncodingHeader <- request.headers.get(ACCEPT_ENCODING)
        encoding <- acceptEncodingHeader.split(',')
      } yield encoding.trim

      def acceptGzipEncoding(request: RequestHeader): Boolean = acceptEncodings(request).contains("gzip")

      val gzippedResource = Play.resource(resourceName + ".gz")

      val resource = gzippedResource.map(_ -> true)
          .filter(_ => Play.isProd && acceptGzipEncoding(request))
          .orElse(Play.resource(resourceName).map(_ -> false))

      def maybeNotModified(url: java.net.URL) = {
        val etagResult = for {
          ifNoneMatchHeader <- request.headers.get(IF_NONE_MATCH)
          etag <- etagFor(url)
          if ifNoneMatchHeader == etag
        } yield cacheableResult(url, NotModified)

        lazy val lastModifiedResult = for {
          ifModifiedSinceHeader <- request.headers.get(IF_MODIFIED_SINCE)
          ifModifiedSince <- parseDate(ifModifiedSinceHeader)
          cachedLastModified <- lastModifiedFor(url)
          lastModified <- parseDate(cachedLastModified)
          if ! lastModified.after(ifModifiedSince)
        } yield NotModified.withHeaders(DATE -> df.print((new java.util.Date).getTime))

        etagResult.orElse(lastModifiedResult)
      }

      def cacheableResult[A <: Result](url: java.net.URL, r: A) = {
        // Add Etag if we are able to compute it
        val taggedResponse = etagFor(url).map(etag => r.withHeaders(ETAG -> etag)).getOrElse(r)
        val lastModifiedResponse = lastModifiedFor(url).map(lastModified => taggedResponse.withHeaders(LAST_MODIFIED -> lastModified)).getOrElse(taggedResponse)

        // Add Cache directive if configured
        val cachedResponse = lastModifiedResponse.withHeaders(CACHE_CONTROL -> {
          Play.configuration.getString("\"assets.cache." + resourceName + "\"").getOrElse(Play.mode match {
            case Mode.Prod => Play.configuration.getString("assets.defaultCache").getOrElse("max-age=3600")
            case _ => "no-cache"
          })
        })
        cachedResponse
      }

      resource.map {

        case (url, _) if new File(url.getFile).isDirectory => NotFound

        case (url, isGzipped) => {

          lazy val (length, resourceData) = {
            val stream = url.openStream()
            try {
              (stream.available, Enumerator.fromStream(stream))
            } catch {
              case _: Throwable => (-1, Enumerator[Array[Byte]]())
            }
          }

          if (length == -1) {
            NotFound
          } else {
            maybeNotModified(url).getOrElse {
              // Prepare a streamed response
              val response = SimpleResult(
                ResponseHeader(OK, Map(
                  CONTENT_LENGTH -> length.toString,
                  CONTENT_TYPE -> MimeTypes.forFileName(file).map(m => m + addCharsetIfNeeded(m)).getOrElse(BINARY),
                  DATE -> df.print({ new java.util.Date }.getTime))),
                resourceData)

              // If there is a gzipped version, even if the client isn't accepting gzip, we need to specify the
              // Vary header so proxy servers will cache both the gzip and the non gzipped version
              val gzippedResponse = (gzippedResource.isDefined, isGzipped) match {
                case (true, true) => response.withHeaders(VARY -> ACCEPT_ENCODING, CONTENT_ENCODING -> "gzip")
                case (true, false) => response.withHeaders(VARY -> ACCEPT_ENCODING)
                case _ => response
              }
              cacheableResult(url, gzippedResponse)
            }
          }

        }

      }.getOrElse(NotFound)

    }
  }

  // -- LastModified handling

  private val lastModifieds = (new java.util.concurrent.ConcurrentHashMap[String, String]()).asScala

  private def lastModifiedFor(resource: java.net.URL): Option[String] = {
    def formatLastModified(lastModified: Long): String = df.print(lastModified)

    def maybeLastModified(resource: java.net.URL): Option[Long] = {
      resource.getProtocol match {
        case "file" => Some(new File(resource.getPath).lastModified)
        case "jar" => {
          Option(resource.openConnection)
            .map(_.asInstanceOf[JarURLConnection].getJarEntry.getTime)
            .filterNot(_ == -1)
        }
        case _ => None
      }
    }

    def cachedLastModified(resource: java.net.URL)(orElseAction: => Option[String]): Option[String] =
      lastModifieds.get(resource.toExternalForm).orElse(orElseAction)

    def setAndReturnLastModified(resource: java.net.URL): Option[String] = {
      val mlm = maybeLastModified(resource).map(formatLastModified)
      mlm.foreach(lastModifieds.put(resource.toExternalForm, _))
      mlm
    }

    if (Play.isProd) cachedLastModified(resource) { setAndReturnLastModified(resource) }
    else setAndReturnLastModified(resource)
  }

  // -- ETags handling

  private val etags = (new java.util.concurrent.ConcurrentHashMap[String, String]()).asScala

  private def etagFor(resource: java.net.URL): Option[String] = {
    etags.get(resource.toExternalForm).filter(_ => Play.isProd).orElse {
      val maybeEtag = lastModifiedFor(resource).map(_ + " -> " + resource.toExternalForm).map("\"" + Codecs.sha1(_) + "\"")
      maybeEtag.foreach(etags.put(resource.toExternalForm, _))
      maybeEtag
    }
  }

}

