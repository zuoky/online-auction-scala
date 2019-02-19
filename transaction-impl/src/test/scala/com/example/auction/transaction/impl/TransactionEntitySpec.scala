package com.example.auction.transaction.impl

import java.time.Duration
import java.util.UUID

import akka.actor.ActorSystem
import com.example.auction.item.api.ItemData
import com.example.auction.transaction.impl
import com.lightbend.lagom.scaladsl.api.transport.Forbidden
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import com.lightbend.lagom.scaladsl.testkit.PersistentEntityTestDriver
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class TransactionEntitySpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("TransactionEntitySpec", JsonSerializerRegistry.actorSystemSetupFor(TransactionSerializerRegistry))

  private val itemId = UUID.randomUUID()
  private val creator = UUID.randomUUID()
  private val winner = UUID.randomUUID()
  private val itemData = ItemData("title", "desc", "EUR", 1, 10, Duration.ofMinutes(10), None)
  private val deliveryData = DeliveryData("Addr1", "Addr2", "City", "State", 27, "Country")
  private val deliveryPrice = 500
  private val payment = OfflinePayment("Payment sent via wire transfer")

  private val transaction = Transaction(itemId, creator, winner, itemData, 2000)
  private val startTransaction = StartTransaction(transaction)
  private val submitDeliveryDetails = SubmitDeliveryDetails(winner, deliveryData)
  private val setDeliveryPrice = SetDeliveryPrice(creator, deliveryPrice)
  private val approveDeliveryDetails = ApproveDeliveryDetails(creator)
  private val submitPaymentDetails = SubmitPaymentDetails(winner, payment)
  private val approvePayment = SubmitPaymentStatus(creator, PaymentStatus.Approved)
  private val rejectPayment = SubmitPaymentStatus(creator, PaymentStatus.Rejected)
  private val getTransaction = GetTransaction(creator)

  private def withTestDriver(block: PersistentEntityTestDriver[TransactionCommand, TransactionEvent, TransactionState] => Unit): Unit = {
    val driver = new PersistentEntityTestDriver(system, new TransactionEntity, itemId.toString)
    block(driver)
    if (driver.getAllIssues.nonEmpty) {
      driver.getAllIssues.foreach(println)
      fail(s"There were issues ${driver.getAllIssues.head}")
    }
  }

  "The transaction entity" should {

    "emit event when creating transaction" in withTestDriver { driver =>
      val outcome = driver.run(startTransaction)
      outcome.state.status should ===(TransactionStatus.NegotiatingDelivery)
      outcome.state.transaction should ===(Some(transaction))
      outcome.events should contain only TransactionStarted(itemId, transaction)
    }

    "emit event when submitting delivery details" in withTestDriver { driver =>
      driver.run(startTransaction)
      val outcome = driver.run(submitDeliveryDetails)
      outcome.state.status should ===(TransactionStatus.NegotiatingDelivery)
      outcome.events should contain only DeliveryDetailsSubmitted(itemId, deliveryData)
    }

    "forbid submitting delivery details by non-buyer" in withTestDriver { driver =>
      driver.run(startTransaction)
      val hacker = UUID.randomUUID()
      val invalid = SubmitDeliveryDetails(hacker, deliveryData)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

    "emit event when setting delivery price" in withTestDriver { driver =>
      driver.run(startTransaction)
      val outcome = driver.run(setDeliveryPrice)
      outcome.state.status should ===(TransactionStatus.NegotiatingDelivery)
      outcome.state.transaction.get.deliveryPrice.get should ===(deliveryPrice)
      outcome.events should contain only DeliveryPriceUpdated(itemId, deliveryPrice)
    }

    "forbid setting delivery price by non-seller" in withTestDriver { driver =>
      driver.run(startTransaction)
      val hacker = UUID.randomUUID()
      val invalid = SetDeliveryPrice(hacker, deliveryPrice)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

    "emit event when approving delivery details" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      val outcome = driver.run(approveDeliveryDetails)
      outcome.state.status should ===(TransactionStatus.PaymentPending)
      outcome.events should contain only DeliveryDetailsApproved(itemId)
    }

    "forbid approve delivery details by non-seller" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      val hacker = UUID.randomUUID()
      val invalid = impl.ApproveDeliveryDetails(hacker)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

    "forbid approve empty delivery details" in withTestDriver { driver =>
      driver.run(startTransaction)
      a[Forbidden] should be thrownBy driver.run(approveDeliveryDetails)
    }

    "emit event when submitting payment details" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      driver.run(approveDeliveryDetails)
      val outcome = driver.run(submitPaymentDetails)
      outcome.state.status should ===(TransactionStatus.PaymentSubmitted)
      outcome.state.transaction.get.payment.get should ===(payment)
      outcome.events should contain only PaymentDetailsSubmitted(itemId, payment)
    }

    "forbid submitting payment details by non-buyer" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      driver.run(approveDeliveryDetails)
      val hacker = UUID.randomUUID
      val invalid = SubmitPaymentDetails(hacker, payment)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

    "emit event when approving payment" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      driver.run(approveDeliveryDetails)
      driver.run(submitPaymentDetails)
      val outcome = driver.run(approvePayment)
      outcome.state.status should ===(TransactionStatus.PaymentConfirmed)
      outcome.events should contain only PaymentApproved(itemId)
    }

    "emit event when rejecting payment" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      driver.run(approveDeliveryDetails)
      driver.run(submitPaymentDetails)
      val outcome = driver.run(rejectPayment)
      outcome.state.status should ===(TransactionStatus.PaymentPending)
      outcome.events should contain only PaymentRejected(itemId)
    }

    "forbid submit payment status for non-seller" in withTestDriver { driver =>
      driver.run(startTransaction)
      driver.run(submitDeliveryDetails)
      driver.run(setDeliveryPrice)
      driver.run(approveDeliveryDetails)
      driver.run(submitPaymentDetails)
      val hacker = UUID.randomUUID
      val invalid = SubmitPaymentStatus(hacker, PaymentStatus.Rejected)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

    "allow see transaction by item creator" in withTestDriver { driver =>
      driver.run(startTransaction)
      val outcome = driver.run(getTransaction)
      outcome.replies should contain only outcome.state
    }

    "forbid see transaction by non-winner or non-creator" in withTestDriver { driver =>
      driver.run(startTransaction)
      val hacker = UUID.randomUUID
      val invalid = GetTransaction(hacker)
      a[Forbidden] should be thrownBy driver.run(invalid)
    }

  }

}
