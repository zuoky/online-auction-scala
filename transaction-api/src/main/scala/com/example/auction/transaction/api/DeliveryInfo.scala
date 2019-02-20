package com.example.auction.transaction.api

import play.api.libs.json.{Format, Json}

case class DeliveryInfo(addressLine1: String,
                        addressLine2: String,
                        city: String,
                        state: String,
                        postalCode: Int,
                        country: String
                        // selectedDeliveryOption: DeliveryOption
                       )

object DeliveryInfo {
  implicit val format: Format[DeliveryInfo] = Json.format
}

