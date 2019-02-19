package com.example.auction.transaction.impl

import java.util.UUID

import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import com.example.auction.item.api.{AuctionFinished, ItemEvent, ItemService}
import com.example.auction.security.ServerSecurity.authenticated
import com.example.auction.transaction.api._
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import com.lightbend.lagom.scaladsl.server.ServerServiceCall

import scala.concurrent.{ExecutionContext, Future}

class TransactionServiceImpl(registry: PersistentEntityRegistry, itemService: ItemService)
                            (implicit ec: ExecutionContext) extends TransactionService {

  // Subscribe to the events from the item service.
  itemService.itemEvents.subscribe.atLeastOnce(Flow[ItemEvent].mapAsync(1) {
    case AuctionFinished(itemId, item) =>
      item.auctionWinner match {
        case None =>
          // If an auction doesn't have a winner, then we can't start a transaction
          Future.successful(Done)
        case Some(winner) =>
          val transaction = Transaction(item.id.get, item.creator, winner, item.itemData, item.price.get)
          entityRef(itemId).ask(StartTransaction(transaction))
      }
    case _ => Future.successful(Done)
  })

  override def submitDeliveryDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { deliveryInfo =>
    entityRef(itemId).ask(SubmitDeliveryDetails(userId, TransactionMappers.fromApiDelivery(deliveryInfo)))
  })

  override def setDeliveryPrice(itemId: UUID) = authenticated(userId => ServerServiceCall { deliveryPrice =>
    entityRef(itemId).ask(SetDeliveryPrice(userId, deliveryPrice))
  })

  override def approveDeliveryDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { _ =>
    entityRef(itemId).ask(ApproveDeliveryDetails(userId))
  })

  override def submitPaymentDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { paymentInfo =>
    entityRef(itemId).ask(SubmitPaymentDetails(userId, TransactionMappers.fromApiPayment(paymentInfo)))
  })

  override def submitPaymentStatus(itemId: UUID) = authenticated(userId => ServerServiceCall { paymentInfoStatus =>
    entityRef(itemId).ask(SubmitPaymentStatus(userId, TransactionMappers.fromApi(paymentInfoStatus)))
  })

  override def getTransaction(itemId: UUID) = authenticated(userId => ServerServiceCall { _ =>
    entityRef(itemId)
      .ask(GetTransaction(userId))
      .map(TransactionMappers.toApi)
  })

  override def getTransactionsForUser(status: TransactionInfoStatus.Status, pageNo: Option[Int], pageSize: Option[Int]): ServiceCall[NotUsed, Seq[TransactionSummary]] = ???

  private def entityRef(itemId: UUID) = {
    registry.refFor[TransactionEntity](itemId.toString)
  }

}
