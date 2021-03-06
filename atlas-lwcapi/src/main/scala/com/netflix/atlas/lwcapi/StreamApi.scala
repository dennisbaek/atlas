/*
 * Copyright 2014-2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.lwcapi

import javax.inject.Inject

import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import com.netflix.atlas.akka.CustomDirectives._
import com.netflix.atlas.akka.WebApi
import com.netflix.atlas.json.Json
import com.netflix.atlas.json.JsonSupport
import com.netflix.spectator.api.Registry
import com.typesafe.scalalogging.StrictLogging

class StreamApi @Inject()(
  sm: ActorSubscriptionManager,
  splitter: ExpressionSplitter,
  implicit val actorRefFactory: ActorRefFactory,
  registry: Registry
) extends WebApi
    with StrictLogging {

  import StreamApi._

  def routes: Route = {
    path("lwc" / "api" / "v1" / "stream" / Segment) { streamId =>
      parameters(('name.?, 'expression.?, 'frequency.?)) { (name, expr, frequency) =>
        get {
          complete(handleReq(None, streamId, name, expr, frequency))
        } ~
        post {
          parseEntity(json[ExpressionsRequest]) { req =>
            complete(handleReq(Some(req), streamId, name, expr, frequency))
          }
        }
      }
    }
  }

  private def splitRequest(
    requestOpt: Option[ExpressionsRequest],
    urlExpr: Option[String],
    urlFreq: Option[String]
  ): Map[ExpressionMetadata, List[Subscription]] = {

    val builder = Map.newBuilder[ExpressionMetadata, List[Subscription]]

    val freq = urlFreq.fold(ApiSettings.defaultFrequency)(_.toInt)
    urlExpr.foreach { expr =>
      builder += ExpressionMetadata(expr, freq) -> splitter.split(expr, freq)
    }

    requestOpt.foreach { request =>
      request.expressions.foreach { expr =>
        builder += expr -> splitter.split(expr.expression, expr.frequency)
      }
    }

    builder.result
  }

  private def handleReq(
    req: Option[ExpressionsRequest],
    streamId: String,
    name: Option[String],
    expr: Option[String],
    freqString: Option[String]
  ): HttpResponse = {

    // Drop any other connections that may already be using the same id
    sm.unregister(streamId).foreach { ref =>
      ref ! SSEShutdown(
        s"Dropped: another connection is using the same stream-id: $streamId",
        unsub = false
      )
    }

    // Validate post data. This is done before creating an actor, since
    // creating the actor sends a chunked response, masking any expression
    // parse errors.
    val splits = splitRequest(req, expr, freqString)

    val source = Source.actorPublisher(
      Props(new SSEActor(streamId, name.getOrElse("unknown"), sm, splits, registry))
    )
    val entity = HttpEntity.Chunked(MediaTypes.`text/event-stream`.toContentType, source)
    HttpResponse(StatusCodes.OK, entity = entity)
  }
}

trait SSERenderable {

  def toSSE: String
}

object StreamApi {

  case class ExpressionsRequest(expressions: List[ExpressionMetadata]) extends JsonSupport

  object ExpressionsRequest {

    def fromJson(json: String): ExpressionsRequest = {
      val decoded = Json.decode[ExpressionsRequest](json)
      if (decoded.expressions == null || decoded.expressions.isEmpty)
        throw new IllegalArgumentException("Missing or empty expressions array")
      decoded
    }
  }

  abstract class SSEMessage(msgType: String, what: String, content: JsonSupport)
      extends SSERenderable {

    def toSSE = s"$msgType: $what ${content.toJson}"

    def getWhat: String = what
  }

  // Hello message
  case class HelloContent(streamId: String, instanceId: String) extends JsonSupport

  case class SSEHello(streamId: String, instanceId: String)
      extends SSEMessage("info", "hello", HelloContent(streamId, instanceId))

  // Generic message string
  case class SSEGenericJson(what: String, msg: JsonSupport) extends SSEMessage("data", what, msg)

  // Heartbeat message
  case class StatisticsContent(outputFullFailures: Long) extends JsonSupport

  case class SSEStatistics(outputFullFailures: Long)
      extends SSEMessage("info", "statistics", StatisticsContent(outputFullFailures))

  // Shutdown message
  case class ShutdownReason(reason: String) extends JsonSupport

  case class SSEShutdown(reason: String, private val unsub: Boolean = true)
      extends SSEMessage("info", "shutdown", ShutdownReason(reason)) {

    def shouldUnregister: Boolean = unsub
  }

  // Subscribe message
  case class SubscribeContent(expression: String, metrics: List[ExpressionMetadata])
      extends JsonSupport

  case class SSESubscribe(expr: String, metrics: List[ExpressionMetadata])
      extends SSEMessage("info", "subscribe", SubscribeContent(expr, metrics))

  case class SSEMetricContent(timestamp: Long, id: String, tags: EvaluateApi.TagMap, value: Double)
      extends JsonSupport

  // Evaluate message
  case class SSEMetric(timestamp: Long, data: EvaluateApi.Item)
      extends SSEMessage(
        "data",
        "metric",
        SSEMetricContent(timestamp, data.id, data.tags, data.value)
      )
}
