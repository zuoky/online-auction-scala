package com.example.auction.transaction.impl

import java.time.{Duration, Instant}
import java.util.UUID

import com.datastax.driver.core.utils.UUIDs
import com.example.auction.item.api.ItemStatus.Status
import com.example.auction.item.api._
import com.example.auction.security.ClientSecurity._
import com.example.auction.transaction.api._
import com.lightbend.lagom.scaladsl.api.AdditionalConfiguration
import com.lightbend.lagom.scaladsl.api.transport.{Forbidden, NotFound}
import com.lightbend.lagom.scaladsl.server.LocalServiceLocator
import com.lightbend.lagom.scaladsl.testkit.{ProducerStub, ProducerStubFactory, ServiceTest}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import play.api.Configuration

import scala.concurrent.Await
import scala.concurrent.duration._

class TransactionServiceImplIntegrationTest extends WordSpec with Matchers with Eventually with ScalaFutures with BeforeAndAfterAll {

  var itemProducerStub: ProducerStub[ItemEvent] = _

  val server = ServiceTest.startServer(ServiceTest.defaultSetup.withCassandra(true)) { ctx =>
    new TransactionApplication(ctx) with LocalServiceLocator {

      val stubFactory = new ProducerStubFactory(actorSystem, materializer)
      itemProducerStub = stubFactory.producer[ItemEvent]("item-ItemEvent")

      override lazy val itemService = new ItemStub(itemProducerStub)

      override def additionalConfiguration: AdditionalConfiguration =
        super.additionalConfiguration ++ Configuration.from(Map(
          "cassandra-query-journal.eventual-consistency-delay" -> "0",
          "lagom.circuit-breaker.default.enabled" -> "off"
        ))
    }
  }

  val transactionService = server.serviceClient.implement[TransactionService]

  override def afterAll = server.stop()

  val awaitTimeout = 10.seconds

  def fixture = new {
    val itemId = UUIDs.timeBased()
    val creatorId = UUID.randomUUID()
    val winnerId = UUID.randomUUID()
    val itemPrice = 5000
    val itemData = ItemData("title", "desc", "EUR", 1, 10, Duration.ofMinutes(10), None)
    val item = Item(Some(itemId), creatorId, itemData, Some(itemPrice), ItemStatus.Completed, Some(Instant.now), Some(Instant.now), Some(winnerId))
    val auctionFinished = AuctionFinished(itemId, item)

    val deliveryInfo = DeliveryInfo("ADDR1", "ADDR2", "CITY", "STATE", 27, "COUNTRY")
    val deliveryPrice = 500
    val paymentInfo = OfflinePaymentInfo("Payment sent via wire transfer")

    val transactionInfoStarted = TransactionInfo(itemId, creatorId, winnerId, itemData, itemPrice, None, None, None, TransactionInfoStatus.NegotiatingDelivery)
    val transactionInfoWithDeliveryInfo = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, Some(deliveryInfo), None, None, TransactionInfoStatus.NegotiatingDelivery)
    val transactionInfoWithDeliveryPrice = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, None, Some(deliveryPrice), None, TransactionInfoStatus.NegotiatingDelivery)
    val transactionInfoWithPaymentPending = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, Some(deliveryInfo), Some(deliveryPrice), None, TransactionInfoStatus.PaymentPending)
    val transactionInfoWithPaymentDetails = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, Some(deliveryInfo), Some(deliveryPrice), Some(paymentInfo), TransactionInfoStatus.PaymentSubmitted)
    val transactionInfoWithPaymentApproved = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, Some(deliveryInfo), Some(deliveryPrice), Some(paymentInfo), TransactionInfoStatus.PaymentConfirmed)
    val transactionInfoWithPaymentRejected = TransactionInfo(itemId, creatorId, winnerId, itemData, item.price.get, Some(deliveryInfo), Some(deliveryPrice), Some(paymentInfo), TransactionInfoStatus.PaymentPending)
  }

  "The Transaction service" should {

    "create transaction on auction finished" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      eventually(timeout(Span(10, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoStarted)
      }
    }

    "not create transaction with no winner" in {
      val f = fixture
      val itemIdWithNoWinner = UUID.randomUUID()
      val itemWithNoWinner = Item(Some(itemIdWithNoWinner), f.creatorId, f.itemData, Some(f.itemPrice), ItemStatus.Completed, Some(Instant.now()), Some(Instant.now()), None)
      val auctionFinishedWithNoWinner = AuctionFinished(itemIdWithNoWinner, itemWithNoWinner)
      itemProducerStub.send(auctionFinishedWithNoWinner)
      a[NotFound] should be thrownBy retrieveTransaction(itemIdWithNoWinner, f.creatorId)
    }

    "submit delivery details" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      submitDeliveryDetails(f.itemId, f.winnerId, f.deliveryInfo)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithDeliveryInfo)
      }
    }

    "set delivery price" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      setDeliveryPrice(f.itemId, f.creatorId, f.deliveryPrice)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithDeliveryPrice)
      }
    }

    "approve delivery details" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      submitDeliveryDetails(f.itemId, f.winnerId, f.deliveryInfo)
      setDeliveryPrice(f.itemId, f.creatorId, f.deliveryPrice)
      approveDeliveryDetails(f.itemId, f.creatorId)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithPaymentPending)
      }
    }

    "forbid approve empty delivery details" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      a[Forbidden] should be thrownBy approveDeliveryDetails(f.itemId, f.creatorId)
    }

    "submit payment details" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      submitDeliveryDetails(f.itemId, f.winnerId, f.deliveryInfo)
      setDeliveryPrice(f.itemId, f.creatorId, f.deliveryPrice)
      approveDeliveryDetails(f.itemId, f.creatorId)
      submitPaymentDetails(f.itemId, f.winnerId, f.paymentInfo)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithPaymentDetails)
      }
    }

    "approve payment" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      submitDeliveryDetails(f.itemId, f.winnerId, f.deliveryInfo)
      setDeliveryPrice(f.itemId, f.creatorId, f.deliveryPrice)
      approveDeliveryDetails(f.itemId, f.creatorId)
      submitPaymentDetails(f.itemId, f.winnerId, f.paymentInfo)
      approvePayment(f.itemId, f.creatorId)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithPaymentApproved)
      }
    }

    "reject payment" in {
      val f = fixture
      itemProducerStub.send(f.auctionFinished)
      submitDeliveryDetails(f.itemId, f.winnerId, f.deliveryInfo)
      setDeliveryPrice(f.itemId, f.creatorId, f.deliveryPrice)
      approveDeliveryDetails(f.itemId, f.creatorId)
      submitPaymentDetails(f.itemId, f.winnerId, f.paymentInfo)
      rejectPayment(f.itemId, f.creatorId)
      eventually(timeout(Span(15, Seconds))) {
        retrieveTransaction(f.itemId, f.creatorId) should ===(f.transactionInfoWithPaymentRejected)
      }
    }

  }

  private def retrieveTransaction(itemId: UUID, creatorId: UUID): TransactionInfo = {
    Await.result(
      transactionService.getTransaction(itemId)
        .handleRequestHeader(authenticate(creatorId))
        .invoke(),
      awaitTimeout
    )
  }

  private def submitDeliveryDetails(itemId: UUID, winnerId: UUID, deliveryInfo: DeliveryInfo): Unit = {
    Await.result(
      transactionService.submitDeliveryDetails(itemId)
        .handleRequestHeader(authenticate(winnerId))
        .invoke(deliveryInfo),
      awaitTimeout
    )
  }

  private def setDeliveryPrice(itemId: UUID, creatorId: UUID, deliveryPrice: Int): Unit = {
    Await.result(
      transactionService.setDeliveryPrice(itemId)
        .handleRequestHeader(authenticate(creatorId))
        .invoke(deliveryPrice),
      awaitTimeout
    )
  }

  private def approveDeliveryDetails(itemId: UUID, creatorId: UUID): Unit = {
    Await.result(
      transactionService.approveDeliveryDetails(itemId)
        .handleRequestHeader(authenticate(creatorId))
        .invoke(),
      awaitTimeout
    )
  }

  private def submitPaymentDetails(itemId: UUID, winnerId: UUID, paymentInfo: PaymentInfo): Unit = {
    Await.result(
      transactionService.submitPaymentDetails(itemId)
        .handleRequestHeader(authenticate(winnerId))
        .invoke(paymentInfo),
      awaitTimeout
    )
  }

  private def approvePayment(itemId: UUID, creatorId: UUID) = {
    Await.result(
      transactionService.submitPaymentStatus(itemId)
        .handleRequestHeader(authenticate(creatorId))
        .invoke(PaymentInfoStatus.Approved),
      awaitTimeout
    )
  }

  private def rejectPayment(itemId: UUID, creatorId: UUID) = {
    Await.result(
      transactionService.submitPaymentStatus(itemId)
        .handleRequestHeader(authenticate(creatorId))
        .invoke(PaymentInfoStatus.Rejected),
      awaitTimeout
    )
  }

}

class ItemStub(itemProducerStub: ProducerStub[ItemEvent]) extends ItemService {

  override def createItem = ???

  override def startAuction(id: UUID) = ???

  override def getItem(id: UUID) = ???

  override def getItemsForUser(id: UUID, status: Status, page: Option[String]) = ???

  override def itemEvents = itemProducerStub.topic

}
