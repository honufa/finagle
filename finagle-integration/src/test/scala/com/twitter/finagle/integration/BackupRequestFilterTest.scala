package com.twitter.finagle.integration

import com.twitter.conversions.DurationOps._
import com.twitter.conversions.PercentOps._
import com.twitter.finagle.context.BackupRequest
import com.twitter.finagle.integration.thriftscala.Echo
import com.twitter.finagle.service.RetryBudget
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Http, Service, ThriftMux, http}
import com.twitter.util.{Await, Future}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.FunSuite
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

class BackupRequestFilterTest extends FunSuite with Eventually with IntegrationPatience {

  private def await[T](f: Future[T]): T = Await.result(f, 15.seconds)

  test("Http client propagates BackupRequest context") {
    val goSlow = new AtomicBoolean(false)
    val backupsSeen = new AtomicInteger(0)
    val service = Service.mk { request: http.Request =>
      val response = Future.value(http.Response())
      if (BackupRequest.wasInitiated) {
        backupsSeen.incrementAndGet()
      }
      val slow = goSlow.get
      goSlow.set(!slow)
      if (slow) {
        response.delayed(100.milliseconds)(DefaultTimer)
      } else {
        response
      }
    }

    val server = Http.server.serve("localhost:*", service)
    val addr = server.boundAddress.asInstanceOf[InetSocketAddress]

    val statsRecv = new InMemoryStatsReceiver()
    val client = Http.client
      .withStatsReceiver(statsRecv)
      .withRetryBudget(RetryBudget.Infinite)
      .withLabel("backend")
      .methodBuilder(s"${addr.getHostName}:${addr.getPort}")
      .idempotent(99.percent)
      .newService

    // warm up the backup filter to have some data points.
    0.until(100).foreach { i =>
      withClue(s"warmup $i") {
        await(client(http.Request("/")))
      }
    }

    // capture state and tee it up.
    goSlow.set(true)
    val counter = statsRecv.counter("backend", "backups", "backups_sent")
    val backupsBefore = counter()
    val backupsSeenBefore = backupsSeen.get
    await(client(http.Request("/")))
    assert(backupsSeen.get == backupsSeenBefore + 1)
    eventually {
      assert(counter() == backupsBefore + 1)
    }
  }

  test("ThriftMux client propagates BackupRequest context") {
    val goSlow = new AtomicBoolean(false)
    val backupsSeen = new AtomicInteger(0)
    val service = new Echo.MethodPerEndpoint {
      def echo(msg: String): Future[String] = {
        val response = Future.value(msg)
        if (BackupRequest.wasInitiated) {
          backupsSeen.incrementAndGet()
        }
        val slow = goSlow.get
        goSlow.set(!slow)
        if (slow) {
          response.delayed(100.milliseconds)(DefaultTimer)
        } else {
          response
        }
      }
    }

    val server = ThriftMux.server.serveIface("localhost:*", service)
    val addr = server.boundAddress.asInstanceOf[InetSocketAddress]

    val statsRecv = new InMemoryStatsReceiver()
    val client = ThriftMux.client
      .withStatsReceiver(statsRecv)
      .withRetryBudget(RetryBudget.Infinite)
      .withLabel("backend")
      .methodBuilder(s"${addr.getHostName}:${addr.getPort}")
      .idempotent(99.percent)
      .servicePerEndpoint[Echo.ServicePerEndpoint]

    // warm up the backup filter to have some data points.
    0.until(100).foreach { i =>
      withClue(s"warmup $i") {
        await(client.echo(Echo.Echo.Args("hi")))
      }
    }

    // capture state and tee it up.
    goSlow.set(true)
    val counter = statsRecv.counter("backend", "backups", "backups_sent")
    val backupsBefore = counter()
    val backupsSeenBefore = backupsSeen.get
    await(client.echo(Echo.Echo.Args("hi")))
    assert(backupsSeen.get == backupsSeenBefore + 1)
    eventually {
      assert(counter() == backupsBefore + 1)
    }
  }

}
