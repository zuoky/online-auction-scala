package com.example.auction.transaction.api

import java.util.UUID

import akka.{Done, NotUsed}
import com.example.auction.security.SecurityHeaderFilter
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}

/**
  * The transaction services.
  *
  * Handles the transaction of negotiating delivery info and making payment of an item that has completed an auction.
  *
  * A transaction is created when an AuctionFinished event is received
  */
trait TransactionService extends Service {

  val TopicId = "transaction-TransactionEvent"

  // def sendMessage(itemId: UUID): ServiceCall[TransactionMessage, Done]

  def submitDeliveryDetails(itemId: UUID): ServiceCall[DeliveryInfo, Done]

  def setDeliveryPrice(itemId: UUID): ServiceCall[Int, Done]

  def approveDeliveryDetails(itemId: UUID): ServiceCall[NotUsed, Done]

  def submitPaymentDetails(itemId: UUID): ServiceCall[PaymentInfo, Done]

  def submitPaymentStatus(itemId: UUID): ServiceCall[PaymentInfoStatus.Status, Done]

  // def dispatchItem(itemId: UUID): ServiceCall[NotUsed, Done]

  // def receiveItem(itemId: UUID): ServiceCall[NotUsed, Done]

  // def initiateRefund(itemId: UUID): ServiceCall[NotUsed, Done]

  def getTransaction(itemId: UUID): ServiceCall[NotUsed, TransactionInfo]

  def getTransactionsForUser(status: TransactionInfoStatus.Status, pageNo: Option[Int], pageSize: Option[Int]): ServiceCall[NotUsed, Seq[TransactionSummary]]

  /**
    * The transaction events topic.
    */
  // def transactionEvents: Topic[TransactionEvent]

  override def descriptor: Descriptor = {
    import Service._

    named("transaction")
      .withCalls(
        pathCall("/api/transaction/:id/deliverydetails", submitDeliveryDetails _),
        pathCall("/api/transaction/:id/deliveryprice", setDeliveryPrice _),
        pathCall("/api/transaction/:id/approvedelivery", approveDeliveryDetails _),
        pathCall("/api/transaction/:id/paymentdetails", submitPaymentDetails _),
        pathCall("/api/transaction/:id/paymentstatus", submitPaymentStatus _),
        pathCall("/api/transaction/:id", getTransaction _),
        pathCall("/api/transaction?status&pageNo&pageSize", getTransactionsForUser _)
      )
      .withHeaderFilter(SecurityHeaderFilter.Composed)

  }
}
