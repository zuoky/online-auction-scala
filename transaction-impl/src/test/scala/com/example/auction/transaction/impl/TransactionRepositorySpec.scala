package com.example.auction.transaction.impl

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.persistence.query.Sequence
import com.datastax.driver.core.utils.UUIDs
import com.example.auction.item.api.ItemData
import com.example.auction.transaction.api.{TransactionInfoStatus, TransactionSummary}
import com.example.auction.utils.PaginatedSequence
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.testkit.{ReadSideTestDriver, ServiceTest}
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TransactionRepositorySpec extends AsyncWordSpec with BeforeAndAfterAll with Matchers {

  private val server = ServiceTest.startServer(ServiceTest.defaultSetup.withCassandra(enabled = true)) { ctx =>
    new TransactionApplication(ctx) {

      override def serviceLocator = NoServiceLocator

      override lazy val readSide = new ReadSideTestDriver

    }
  }

  override def afterAll() = server.stop()

  private val testDriver = server.application.readSide
  private val transactionRepository = server.application.transactionRepository
  private val offset = new AtomicInteger()

  private val itemId = UUIDs.timeBased()
  private val creatorId = UUID.randomUUID
  private val winnerId = UUID.randomUUID
  private val itemTitle = "title"
  private val currencyId = "EUR"
  private val itemPrice = 2000
  private val itemData = ItemData(itemTitle, "desc", currencyId, 1, 10, Duration.ofMinutes(10), None)
  private val transaction = Transaction(itemId, creatorId, winnerId, itemData, itemPrice)

  private val deliveryData = DeliveryData("Addr1", "Addr2", "City", "State", 27, "Country")
  private val deliveryPrice = 500
  private val payment = OfflinePayment("Payment sent via wire transfer")

  "The transaction repository" should {

    "get transaction started for creator" in {
      shouldGetTransactionStarted(creatorId)
    }

    "get transaction started for winner" in {
      shouldGetTransactionStarted(winnerId)
    }

    "update status to payment pending for creator" in {
      shouldUpdateStatusToPaymentPending(creatorId)
    }

    "update status to payment pending for winner" in {
      shouldUpdateStatusToPaymentPending(winnerId)
    }

    "update status to payment submitted for creator" in {
      shouldUpdateStatusToPaymentSubmitted(creatorId)
    }

    "update status to payment submitted for winner" in {
      shouldUpdateStatusToPaymentSubmitted(winnerId)
    }

    "update status to payment confirmed after approval for creator" in {
      shouldUpdateStatusToPaymentConfirmedAfterApproval(creatorId)
    }

    "update status to payment confirmed after approval for winner" in {
      shouldUpdateStatusToPaymentConfirmedAfterApproval(winnerId)
    }

    "update status to payment pending after rejection for creator" in {
      shouldUpdateStatusToPaymentPendingAfterRejection(creatorId)
    }

    "update status to payment pending after rejection for winner" in {
      shouldUpdateStatusToPaymentPendingAfterRejection(winnerId)
    }

    "paginate transaction retrieval" in {
      for (i <- 0 until 25) {
        val itemId = UUIDs.timeBased()
        val transaction = buildTransactionFixture(itemId, creatorId, i)
        Await.result(feed(TransactionStarted(itemId, transaction)), 10.seconds)
      }
      for (i <- 0 until 25) {
        val itemId = UUIDs.timeBased()
        val transaction = buildTransactionFixture(itemId, winnerId, i)
        Await.result(feed(TransactionStarted(itemId, transaction)), 10.seconds)
      }
      transactionRepository.getTransactionsForUser(
        creatorId, TransactionInfoStatus.NegotiatingDelivery, 1, 10
      ).map { result =>
        result.count should ===(25)
        result.items.size should ===(10)
        // default ordering is time DESC so page 2 of size 10 over a set of 25 returns item ids 5-14. On that seq, the fifth item is id=10
        result.items(4).itemTitle should ===("title10")
      }
      transactionRepository.getTransactionsForUser(
        winnerId, TransactionInfoStatus.NegotiatingDelivery, 0, 10
      ).map { result =>
        result.count should ===(25)
        result.items.size should ===(10)
        // default ordering is time DESC so page 0 of size 10 over a set of 25 returns item ids 15-24. On that seq, the third item is id=22
        result.items(2).itemTitle should ===("title22")
      }
    }

  }

  private def shouldGetTransactionStarted(userId: UUID) = {
    for {
      _ <- feed(TransactionStarted(itemId, transaction))
      transactions <- getTransactions(userId, TransactionInfoStatus.NegotiatingDelivery)
    } yield {
      transactions.count should ===(1)
      val expected = new TransactionSummary(itemId, creatorId, winnerId, itemTitle, currencyId, itemPrice, TransactionInfoStatus.NegotiatingDelivery)
      transactions.items.head should ===(expected)
    }
  }

  private def shouldUpdateStatusToPaymentPending(userId: UUID) = {
    for {
      _ <- feed(TransactionStarted(itemId, transaction))
      _ <- feed(DeliveryDetailsSubmitted(itemId, deliveryData))
      _ <- feed(DeliveryPriceUpdated(itemId, deliveryPrice))
      _ <- feed(DeliveryDetailsApproved(itemId))
      transactions <- getTransactions(userId, TransactionInfoStatus.PaymentPending)
    } yield {
      transactions.count should ===(1)
      val expected = TransactionSummary(itemId, creatorId, winnerId, itemTitle, currencyId, itemPrice, TransactionInfoStatus.PaymentPending)
      transactions.items.head should ===(expected)
    }
  }

  private def shouldUpdateStatusToPaymentSubmitted(userId: UUID) = {
    for {
      _ <- feed(TransactionStarted(itemId, transaction))
      _ <- feed(DeliveryDetailsSubmitted(itemId, deliveryData))
      _ <- feed(DeliveryPriceUpdated(itemId, deliveryPrice))
      _ <- feed(DeliveryDetailsApproved(itemId))
      _ <- feed(PaymentDetailsSubmitted(itemId, payment))
      transactions <- getTransactions(userId, TransactionInfoStatus.PaymentSubmitted)
    } yield {
      transactions.count should ===(1)
      val expected = TransactionSummary(itemId, creatorId, winnerId, itemTitle, currencyId, itemPrice, TransactionInfoStatus.PaymentSubmitted)
      transactions.items.head should ===(expected)
    }
  }

  private def shouldUpdateStatusToPaymentConfirmedAfterApproval(userId: UUID) = {
    for {
      _ <- feed(TransactionStarted(itemId, transaction))
      _ <- feed(DeliveryDetailsSubmitted(itemId, deliveryData))
      _ <- feed(DeliveryPriceUpdated(itemId, deliveryPrice))
      _ <- feed(DeliveryDetailsApproved(itemId))
      _ <- feed(PaymentDetailsSubmitted(itemId, payment))
      _ <- feed(PaymentApproved(itemId))
      transactions <- getTransactions(userId, TransactionInfoStatus.PaymentConfirmed)
    } yield {
      transactions.count should ===(1)
      val expected = TransactionSummary(itemId, creatorId, winnerId, itemTitle, currencyId, itemPrice, TransactionInfoStatus.PaymentConfirmed)
      transactions.items.head should ===(expected)
    }
  }

  private def shouldUpdateStatusToPaymentPendingAfterRejection(userId: UUID) = {
    for {
      _ <- feed(TransactionStarted(itemId, transaction))
      _ <- feed(DeliveryDetailsSubmitted(itemId, deliveryData))
      _ <- feed(DeliveryPriceUpdated(itemId, deliveryPrice))
      _ <- feed(DeliveryDetailsApproved(itemId))
      _ <- feed(PaymentDetailsSubmitted(itemId, payment))
      _ <- feed(PaymentRejected(itemId))
      transactions <- getTransactions(userId, TransactionInfoStatus.PaymentPending)
    } yield {
      transactions.count should ===(1)
      val expected = TransactionSummary(itemId, creatorId, winnerId, itemTitle, currencyId, itemPrice, TransactionInfoStatus.PaymentPending)
      transactions.items.head should ===(expected)
    }
  }

  private def getTransactions(userId: UUID, transactionStatus: TransactionInfoStatus.Status): Future[PaginatedSequence[TransactionSummary]] = {
    transactionRepository.getTransactionsForUser(userId, transactionStatus, 0, 10)
  }

  private def buildTransactionFixture(userId: UUID, creatorId: UUID, id: Int): Transaction = {
    val data = ItemData(s"title$id", s"desc$id", "USD", 10, 100, Duration.ofMinutes(10), None)
    Transaction(itemId, creatorId, UUID.randomUUID(), data, 2000)
  }

  private def feed(event: TransactionEvent): Future[Done] = {
    testDriver.feed(event.itemId.toString, event, Sequence(offset.getAndIncrement))
  }

}
