package com.example.auction.transaction.api

import java.util.UUID

import com.example.auction.item.api.ItemData
import com.example.auction.utils.JsonFormats.enumFormat
import com.lightbend.lagom.scaladsl.api.deser.PathParamSerializer
import play.api.libs.json.{Format, Json}

case class TransactionInfo(itemId: UUID,
                           creator: UUID,
                           winner: UUID,
                           itemData: ItemData,
                           itemPrice: Int,
                           deliveryInfo: Option[DeliveryInfo],
                           deliveryPrice: Option[Int],
                           paymentInfo: Option[PaymentInfo],
                           status: TransactionInfoStatus.Status)

object TransactionInfo {
  implicit val format: Format[TransactionInfo] = Json.format
}

object TransactionInfoStatus extends Enumeration {

  /**
    * Negotiating delivery details.
    */
  val NegotiatingDelivery,

  /**
    * Seller approved delivery details and payment is pending
    */
  PaymentPending,

  /**
    * Buyer has submitted payment details.
    */
  PaymentSubmitted,

  /**
    * Payment is confirmed.
    */
  PaymentConfirmed,

  /**
    * Item is dispatched.
    */
  ItemDispatched,

  /**
    * Item has been received.
    */
  ItemReceived,

  /**
    * The transaction has been cancelled.
    */
  Cancelled,

  /**
    * The transaction is to be refunded.
    */
  Refunding,

  /**
    * The transaction has been refunded.
    */
  Refunded = Value

  type Status = Value
  implicit val format: Format[Status] = enumFormat(TransactionInfoStatus)
  implicit val pathParamSerializer: PathParamSerializer[Status] =
    PathParamSerializer.required("TransactionInfoStatus")(withName)(_.toString)
}
