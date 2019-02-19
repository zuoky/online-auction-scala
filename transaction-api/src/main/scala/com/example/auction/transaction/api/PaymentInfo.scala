package com.example.auction.transaction.api

import com.example.auction.utils.JsonFormats.enumFormat
import julienrf.json.derived
import play.api.libs.json._

sealed trait PaymentInfo

case class OfflinePaymentInfo(comment: String) extends PaymentInfo

object OfflinePaymentInfo {
  implicit val format: Format[OfflinePaymentInfo] = Json.format
}

object PaymentInfoStatus extends Enumeration {
  val Approved, Rejected = Value
  type Status = Value
  implicit val format: Format[Status] = enumFormat(PaymentInfoStatus)
}

object PaymentInfo {
  implicit val format: Format[PaymentInfo] =
    derived.flat.oformat((__ \ "type").format[String])
}
