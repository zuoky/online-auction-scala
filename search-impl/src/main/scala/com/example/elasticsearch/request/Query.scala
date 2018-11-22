package com.example.elasticsearch.request

import play.api.libs.json.{Format, Json}


case class BooleanQuery(must_not: Filter, must: Seq[Filter])

case class Query(bool: BooleanQuery)

case class QueryRoot(from: Int,
                     size: Int,
                     query: Query,
                     sort: Seq[SortField] = Seq(Sorters.auctionEndDescending(), Sorters.priceAscending())
                    ) {
  val pageNumber: Int = from
  val pageSize: Int = size
}


object BooleanQuery {
  implicit val format: Format[BooleanQuery] = Json.format
}

object Query {
  implicit val format: Format[Query] = Json.format
}

object QueryRoot {
  implicit val format: Format[QueryRoot] = Json.format
}

