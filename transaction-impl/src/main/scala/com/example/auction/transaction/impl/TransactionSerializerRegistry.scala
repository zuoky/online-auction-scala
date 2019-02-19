package com.example.auction.transaction.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}

object TransactionSerializerRegistry extends JsonSerializerRegistry {

  override def serializers = List(
    // State
    JsonSerializer[TransactionState],
    // Commands and replies
    JsonSerializer[GetTransaction],
    JsonSerializer[StartTransaction],
    JsonSerializer[SubmitDeliveryDetails],
    JsonSerializer[SetDeliveryPrice],
    JsonSerializer[ApproveDeliveryDetails],
    JsonSerializer[SubmitPaymentDetails],
    JsonSerializer[SubmitPaymentStatus],
    // Events
    JsonSerializer[TransactionStarted],
    JsonSerializer[DeliveryDetailsSubmitted],
    JsonSerializer[DeliveryPriceUpdated],
    JsonSerializer[DeliveryDetailsApproved],
    JsonSerializer[PaymentDetailsSubmitted],
    JsonSerializer[PaymentApproved],
    JsonSerializer[PaymentRejected]
  )

}
