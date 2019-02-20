package com.example.auction.transaction.api

import java.util.UUID

sealed trait TransactionEvent {
  val itemId: UUID
}

case class DeliveryByNegotiation(itemId: UUID) extends TransactionEvent

case class DeliveryPriceUpdated(itemId: UUID) extends TransactionEvent

case class PaymentConfirmed(itemId: UUID) extends TransactionEvent

case class PaymentFailed(itemId: UUID) extends TransactionEvent

case class ItemDispatched(itemId: UUID) extends TransactionEvent

case class ItemReceived(itemId: UUID) extends TransactionEvent

case class MessageSent(itemId: UUID, message: TransactionMessage) extends TransactionEvent

case class RefundConfirmed(itemId: UUID) extends TransactionEvent

