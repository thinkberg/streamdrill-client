/*
 * Copyright (c) 2015 streamdrill UG (haftungsbeschraenkt). All rights reserved.
 */

package streamdrill.client

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import org.apache.commons.codec.binary.Base64
import java.net.{URLEncoder, HttpURLConnection, URL}
import streamdrill.json.{JSONObject, JSONParser, JSONWriter}

import io.Source
import java.net.HttpURLConnection.{HTTP_OK, HTTP_CREATED}
import grizzled.slf4j.Logging
import java.util.{TimeZone, Locale, Date}
import java.io.FileNotFoundException
import scala.collection.JavaConverters._
import java.text.{DateFormat, SimpleDateFormat}

/**
 * A streaming connection to the trend update.
 *
 * @param conn connection used for streaming the data
 */
class StreamDrillClientStream(conn: HttpURLConnection) extends Logging {
  conn.addRequestProperty("Content-type", "application/json")
  conn.setChunkedStreamingMode(8192)

  private val os = conn.getOutputStream

  private var lastWrittenTimestamp = System.nanoTime()
  val keepAliveMonitor = new Runnable {
    var alive = true

    def run() {
      info("setting up keepalive monitor for %s".format(conn.getURL))

      while (alive) {
        Thread.sleep(10000L)
        if (System.nanoTime - lastWrittenTimestamp > 10e9) {
          lastWrittenTimestamp = System.nanoTime()
          try {
            os.write("\n".getBytes("UTF-8"))
            os.flush()
          } catch {
            case e: Exception => alive = false
          }
        }
      }
      info("keepalive monitor exited: %s".format(conn.getURL))
    }
  }
  new Thread(keepAliveMonitor).start()

  /**
   * Update an item
   *
   * @param trend  name of the trend
   * @param keys   sequence of keys
   * @param value  a predefined value to be used (optional)
   * @param ts     a time stamp for the object event (optional)
   */
  def update(trend: String, keys: Seq[String], value: Option[Double] = None, ts: Option[Date] = None) {
    lastWrittenTimestamp = System.nanoTime()
    val message = ts match {
      case Some(d) if value.isDefined =>
        JSONWriter.toJSON(Map("t" -> trend, "k" -> keys, "v" -> value.get, "ts" -> ts.get.getTime))
      case Some(d) if value.isEmpty =>
        JSONWriter.toJSON(Map("t" -> trend, "k" -> keys, "ts" -> ts.get.getTime))
      case None if value.isDefined =>
        JSONWriter.toJSON(Map("t" -> trend, "k" -> keys, "v" -> value.get))
      case None =>
        JSONWriter.toJSON(Map("t" -> trend, "k" -> keys))
    }
    os.write((message + "\n").getBytes("UTF-8"))
    os.flush()
  }

  /**
   * Call to close the stream
   *
   * @return some random information string, currently of the form "%d updates, %d updates/s".
   */
  def done(): (Long, Double) = {
    try {keepAliveMonitor.alive = false} catch {case _: Throwable => /* simply ignore it */}
    os.close()
    val result = JSONParser.parse(Source.fromInputStream(conn.getInputStream).mkString)
    (result.getLong("updates"), result.getDouble("rate"))
  }
}


/**
 * StreamDrill client
 */
class StreamDrillClient(serverUrl: String,
                        apiKey: String="f9aaf865-b89a-444d-9070-38ec6666e539",
                        apiSecret: String="9e13e4ac-ad93-4c8f-a896-d5a937b84c8a")
    extends Logging {
  private val AUTHORIZATION = "Authorization"
  private val DATE_RFC1123 = new ThreadLocal[DateFormat]() {
    override def initialValue() = {
      val df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
      df.setTimeZone(TimeZone.getTimeZone("UTC"))
      df
    }
  }


  private[client] def sign(method: String, date: String, url: String, apiSecret: String) = {
    val message = method + "\n" + date + "\n" + url
    val secretKey = new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA1")
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(secretKey)
    Base64.encodeBase64String(mac.doFinal(message.getBytes("UTF-8")))
  }

  private def connectWithAuth(method: String, path: String, queryparams: Map[String, Any] = Map()): HttpURLConnection = {
    val date = DATE_RFC1123.get.format(new Date)
    val formattedQueryParams = if(queryparams.isEmpty)
          ""
        else
          ("?" + queryparams.map(kv => kv._1 + "=" + kv._2).mkString("&"))
    val c = new URL(serverUrl + path + formattedQueryParams).openConnection.asInstanceOf[HttpURLConnection]
    c.setRequestMethod(method)
    c.setRequestProperty("Date", date)
    c.setRequestProperty(AUTHORIZATION, "TPK %s:%s".format(apiKey, sign(method, date, path, apiSecret)))
    c
  }

  /**
   * Timeouts! 5s for connect, 60s for read.
   *
   * @param c
   * @return
   */
  private def readResponseWithTimeouts(c: HttpURLConnection): String = {
    c.setConnectTimeout(5000)
    c.setReadTimeout(60000)
    Source.fromInputStream(c.getInputStream).getLines().mkString("\n")
  }

  private def readJSONResponse(c: HttpURLConnection): JSONObject =
    JSONParser.parse(readResponseWithTimeouts(c))


  private var tokens: Map[String, String] = Map()

  /**
   * Create a new table.
   *
   * @param trend   name of the table
   * @param entity  entitiy of the events
   * @param size    maximum size of the event
   * @return   A tuple of an API Token string and whether trend is new
   */
  def create(trend: String, entity: String, size: Int, timescales: Seq[String]): (String, Boolean) = {
    val path = "/1/create/%s/%s".format(trend, entity)
    //val query = "size=%d&timescales=%s".format(size, URLEncoder.encode(timescales.mkString(","), "UTF-8"))
    val query = Map("size" -> size, "timescales" -> URLEncoder.encode(timescales.mkString(","), "UTF-8"))
    val urlCon = connectWithAuth("GET", path, query)

    val json = readJSONResponse(urlCon)

    val status = urlCon.getResponseCode
    debug("create(%s, %s, %d) => %d, '%s'".format(trend, entity, size, status, json))

    if (status != HTTP_OK && status != HTTP_CREATED)
      throw new java.io.IOException("Return code %d on trend creation".format(status))

    val token = json.getString(trend)
    tokens += (trend -> token)

    (token, status == HTTP_CREATED)
  }

  /**
   * Hit and run update with a single HTTP GET.
   *
   * @param trend  name of the trend
   * @param keys   keys of the item to update
   * @param value  a predefined value to be used (optional)
   * @param ts     a time stamp for the object event (optional)
   * @return       return string (just some non-formatted text)
   */
  def update(trend: String, keys: Seq[String], ts: Option[Date] = None, value: Option[Double] = None): String = {
    val base = "%s/1/update/%s/%s".format(serverUrl, trend, keys.map(URLEncoder.encode(_, "UTF-8")).mkString(":"))
    val url = new StringBuilder(base)
    ts match {
      case Some(d) if value.isDefined =>
        url.append("?").append("v=%f&ts=%d".format(value.get, ts.get.getTime))
      case Some(d) if value.isEmpty =>
        url.append("?").append("ts=%d".format(ts.get.getTime))
      case None if value.isDefined =>
        url.append("?").append("v=%f".format(value.get))
      case None =>
      // neither value nor timestamp declared
    }

    val urlCon = new URL(url.toString()).openConnection().asInstanceOf[HttpURLConnection]
    urlCon.setRequestProperty("Authorization", "APITOKEN " + tokens(trend))
    readResponseWithTimeouts(urlCon)
  }

  /**
   * Query the trend and return a top-n list with scores.
   *
   * @param trend     name of the trend
   * @param count     the number of elements to return
   * @param offset    the offset within the total trend to start from
   * @param timescale the timescale to query (day, hour or minute for example)
   * @param filter    a filter of entities key pairs to only return values for
   * @return          a list of top-n entities and their scores
   */
  def query(trend: String, count: Int = 20, offset: Int = 0, timescale: Option[String] = None, filter: Map[String, String] = Map()): Seq[(Seq[String], Double)] = {
    val path = "/1/query/" + trend
    //var qp = "count=" + count
    //if (offset != 0) qp += "&offset=" + offset
    //if (timescale.isDefined) qp += "&timescale=" + timescale.get
    //if (!filter.isEmpty) qp += "&" + filter.map(kv => "%s=%s".format(kv._1, URLEncoder.encode(kv._2, "UTF-8")))
    //    .mkString("&")
    var qp = Map[String,Any]("count" -> count)
    if (offset != 0) qp += "offset" -> offset
    if (timescale.isDefined) qp += "timescale" -> timescale.get
    if (!filter.isEmpty) qp ++= filter.map(kv  => (kv._1, URLEncoder.encode(kv._2, "UTF-8")))

    val c = connectWithAuth("GET", path, qp)
    val jsonResponse = readJSONResponse(c)
    val json = jsonResponse.get("trend")
    (0 until json.length)
        .map(i => (json.get(i).getArray("keys").asInstanceOf[java.util.List[String]].asScala, json.get(i)
        .getDouble("score")))
  }

  /**
   * Query a score for keys.
   *
   * @param trend     name of the trend
   * @param keys      the keys to query
   * @param ts        an optional tme stamp
   * @param timescale an optional timescale
   */
  def score(trend: String, keys: Seq[String], ts: Option[Date] = None, timescale: Option[String] = None): Double = {
    val path = "/1/query/" + trend + "/score"
    val qp = ts.map("ts" -> _.getTime).toMap
    val c = connectWithAuth("POST", path, qp)
    c.setDoOutput(true)
    c.getOutputStream.write(keys.map(URLEncoder.encode(_, "UTF-8")).mkString(":").getBytes("UTF-8"))
    val json = readJSONResponse(c)
    json.get(0).getDouble("score")
  }

  /**
   * Query a score for a list of keys.
   *
   * @param trend     name of the trend
   * @param keys      the list of keys to query
   * @param ts        an optional time stamp
   * @param timescale an optional timescale
   */
  def scores(trend: String, keys: Seq[Seq[String]], ts: Option[Date] = None, timescale: Option[String] = None): Seq[(Seq[String], Double)] = {
    val path = "/1/query/" + trend + "/score"
    val c = connectWithAuth("POST", path)
    c.setDoOutput(true)
    c.getOutputStream
        .write(keys.map(_.map(URLEncoder.encode(_, "UTF-8")).mkString(":")).mkString("\n").getBytes("UTF-8"))
    val json = readJSONResponse(c)
    (0 until json.length).map { i =>
      (json.get(i).getArray("keys").asInstanceOf[java.util.List[String]].asScala.toIndexedSeq,
          json.get(i).getDouble("score"))
    }
  }

  /**
   * Stream the data to a client.
   *
   * @return a client stream object
   */
  def stream(): StreamDrillClientStream = {
    val c = connectWithAuth("POST", "/1/update")
    c.setDoOutput(true)

    new StreamDrillClientStream(c)
  }

  /**
   * Set meta-information for a trend
   *
   * @param trend name of the trend
   * @param property name of the meta-property to set
   * @param value value of the meta-property
   */
  def setMeta(trend: String, property: String, value: String) {
    val c = connectWithAuth("GET", "/1/meta/%s/%s".format(trend, property), Map("value" -> URLEncoder.encode(value, "UTF-8")))
    readResponseWithTimeouts(c)
  }

  /**
   * Get meta-information for a trend
   *
   * @param trend name of the trend
   * @param property name of the meta-property to get
   * @return returned value
   */
  def getMeta(trend: String, property: String): String = {
    val c = connectWithAuth("GET", "/1/meta/%s/%s".format(trend, property))
    readJSONResponse(c).getString("value")
  }


  def delete(trend: String) {
    try {
      val c = connectWithAuth("DELETE", "/1/delete/%s".format(trend))
      c.setRequestMethod("DELETE")
      readResponseWithTimeouts(c)
    } catch {
      case ex: FileNotFoundException => throw new NoSuchElementException("Trend %s does not exist".format(trend))
    }
  }

  def clear(trend: String) {
    try {
      val c = connectWithAuth("DELETE", "/1/clear/%s".format(trend))
      c.setRequestMethod("DELETE")
      readResponseWithTimeouts(c)
    } catch {
      case ex: FileNotFoundException => throw new NoSuchElementException("Trend %s does not exist".format(trend))
    }
  }
}