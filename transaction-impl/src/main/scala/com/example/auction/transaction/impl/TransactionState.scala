package com.example.auction.transaction.impl

import java.util.UUID

import com.example.auction.item.api.ItemData
import com.example.auction.transaction.api.TransactionInfoStatus
import com.example.auction.utils.JsonFormats.enumFormat
import julienrf.json.derived
import play.api.libs.json._

case class TransactionState(transaction: Option[Transaction], status: TransactionStatus.Status) {

  // FIXME: Ported from Java version

  def updateDeliveryData(deliveryData: DeliveryData): TransactionState = {
    update(i => i.withDeliveryData(deliveryData), status)
  }

  def updateDeliveryPrice(deliveryPrice: Int): TransactionState = {
    update(i => i.withDeliveryPrice(deliveryPrice), status)
  }

  def updatePayment(payment: Payment): TransactionState = {
    update(i => i.withPayment(payment), status)
  }

  def withStatus(status: TransactionStatus.Status): TransactionState = copy(status = status)

  private def update(updateFunction: Transaction => Transaction, status: TransactionStatus.Status): TransactionState = {
    assert(transaction.isDefined)
    TransactionState(transaction.map(updateFunction), status)
  }

}

object TransactionState {
  implicit val format: Format[TransactionState] = Json.format

  // FIXME: Ported from Java version

  def notStarted = TransactionState(None, TransactionStatus.NotStarted)

  def start(transaction: Transaction) = TransactionState(Some(transaction), TransactionStatus.NegotiatingDelivery)

}

case class Transaction(itemId: UUID,
                       creator: UUID,
                       winner: UUID,
                       itemData: ItemData,
                       itemPrice: Int,
                       deliveryData: Option[DeliveryData] = None,
                       deliveryPrice: Option[Int] = None,
                       payment: Option[Payment] = None) {

  def withDeliveryData(deliveryData: DeliveryData): Transaction = {
    copy(deliveryData = Some(deliveryData))
  }

  def withDeliveryPrice(deliveryPrice: Int): Transaction = {
    copy(deliveryPrice = Some(deliveryPrice))
  }

  def withPayment(payment: Payment): Transaction = {
    copy(payment = Some(payment))
  }

}

object Transaction {
  implicit val format: Format[Transaction] = Json.format
}

case class DeliveryData(addressLine1: String,
                        addressLine2: String,
                        city: String,
                        state: String,
                        postalCode: Int,
                        country: String)

object DeliveryData {
  implicit val format: Format[DeliveryData] = Json.format
}

sealed trait Payment

case class OfflinePayment(comment: String) extends Payment

object OfflinePayment {
  implicit val format: Format[OfflinePayment] = Json.format
}

object Payment {
  implicit val format: Format[Payment] =
    derived.flat.oformat((__ \ "type").format[String])
}

object TransactionStatus extends Enumeration {

  val NotStarted, NegotiatingDelivery, PaymentPending, PaymentSubmitted, PaymentConfirmed, ItemDispatched, ItemReceived,
  Cancelled, Refunding, Refunded = Value

  type Status = Value

  implicit val format: Format[Status] = enumFormat(TransactionStatus)

  def transactionInfoStatus(status: Status): TransactionInfoStatus.Value = {
    status match {
      case NegotiatingDelivery => TransactionInfoStatus.NegotiatingDelivery
      case PaymentPending => TransactionInfoStatus.PaymentPending
      case PaymentSubmitted => TransactionInfoStatus.PaymentSubmitted
      case PaymentConfirmed => TransactionInfoStatus.PaymentConfirmed
      case ItemDispatched => TransactionInfoStatus.ItemDispatched
      case ItemReceived => TransactionInfoStatus.ItemReceived
      case Cancelled => TransactionInfoStatus.Cancelled
      case Refunding => TransactionInfoStatus.Refunding
      case Refunded => TransactionInfoStatus.Refunded
      case _ => throw new IllegalStateException(s"Cannot map $status")
    }
  }

}
