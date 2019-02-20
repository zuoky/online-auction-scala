package com.example.auction.transaction.api

import java.util.UUID

import play.api.libs.json.{Format, Json}

case class TransactionSummary(itemId: UUID,
                              creatorId: UUID,
                              winnerId: UUID,
                              itemTitle: String,
                              currencyId: String,
                              itemPrice: Int,
                              status: TransactionInfoStatus.Status)

object TransactionSummary {
  implicit val format: Format[TransactionSummary] = Json.format
}
