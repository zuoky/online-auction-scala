package com.example.auction.transaction.api

sealed trait PaymentEvent

case class PaymentDetailsSubmitted(/*TODO*/) extends PaymentEvent

case class RefundInitiated(/*TODO*/) extends PaymentEvent
