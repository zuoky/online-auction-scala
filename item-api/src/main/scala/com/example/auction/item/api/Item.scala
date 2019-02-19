package com.example.auction.item.api

import java.time.{Duration, Instant}
import java.util.UUID

import com.example.auction.utils.JsonFormats._
import com.lightbend.lagom.scaladsl.api.deser.PathParamSerializer
import play.api.libs.json.{Format, Json}

case class Item(
  id: Option[UUID],
  creator: UUID,
  itemData: ItemData,
  price: Option[Int],
  status: ItemStatus.Status,
  auctionStart: Option[Instant],
  auctionEnd: Option[Instant],
  auctionWinner: Option[UUID]
) {
  def safeId = id.getOrElse(UUID.randomUUID())
}

object Item {
  implicit val format: Format[Item] = Json.format

  def create(
    creator: UUID,
    title: String,
    description: String,
    currencyId: String,
    increment: Int,
    reservePrice: Int,
    auctionDuration: Duration
  ) = Item(None, creator, ItemData(title, description, currencyId, increment, reservePrice, auctionDuration, None), None, ItemStatus.Created, None, None, None)
}

case class ItemData(title: String,
                    description: String,
                    currencyId: String,
                    increment: Int,
                    reservePrice: Int,
                    auctionDuration: Duration,
                    categoryId: Option[UUID]
                   )

object ItemData {
  implicit val format: Format[ItemData] = Json.format
}

object ItemStatus extends Enumeration {
  val Created, Auction, Completed, Cancelled = Value
  type Status = Value

  implicit val format: Format[Value] = enumFormat(this)
  implicit val pathParamSerializer: PathParamSerializer[Status] =
    PathParamSerializer.required("itemStatus")(withName)(_.toString)
}

case class ItemSummary(
  id: UUID,
  title: String,
  currencyId: String,
  reservePrice: Int,
  status: ItemStatus.Status
)

object ItemSummary {
  implicit val format: Format[ItemSummary] = Json.format
}

