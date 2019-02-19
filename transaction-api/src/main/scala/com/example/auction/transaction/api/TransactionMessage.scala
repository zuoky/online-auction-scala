package com.example.auction.transaction.api

import java.time.Instant
import java.util.UUID

case class TransactionMessage(author: UUID, message: String, timeSent: Instant)

