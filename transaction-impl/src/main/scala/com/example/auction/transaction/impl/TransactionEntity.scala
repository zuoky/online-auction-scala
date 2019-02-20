package com.example.auction.transaction.impl

import java.util.UUID

import akka.Done
import com.lightbend.lagom.scaladsl.api.transport.{Forbidden, NotFound}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity

class TransactionEntity extends PersistentEntity {

  override type State = TransactionState
  override type Command = TransactionCommand
  override type Event = TransactionEvent

  override def initialState: TransactionState = TransactionState.notStarted

  override def behavior: Behavior = {
    case TransactionState(_, TransactionStatus.NotStarted) => notStarted
    case TransactionState(_, TransactionStatus.NegotiatingDelivery) => negotiatingDelivery
    case TransactionState(_, TransactionStatus.PaymentPending) => paymentPending
    case TransactionState(_, TransactionStatus.PaymentSubmitted) => paymentSubmitted
    case TransactionState(_, TransactionStatus.PaymentConfirmed) => paymentConfirmed
    case _ => throw new IllegalStateException()
  }

  private val getTransaction = {
    Actions().onReadOnlyCommand[GetTransaction, TransactionState] {
      case (GetTransaction(userId), ctx, state) =>
        if (state.transaction.isDefined) {
          if (userId == state.transaction.get.creator || userId == state.transaction.get.winner) {
            ctx.reply(state)
          } else {
            throw Forbidden("Only the item owner and the auction winner can see transaction details")
          }
        } else {
          throw NotFound(s"Transaction for item $entityId not found")
        }
    }
  }

  private val notStarted = {
    Actions().onCommand[StartTransaction, Done] {
      case (StartTransaction(transaction), ctx, _) =>
        ctx.thenPersist(TransactionStarted(UUID.fromString(entityId), transaction))(_ => ctx.reply(Done))
    }.onEvent {
      case (TransactionStarted(_, transaction), _) =>
        TransactionState.start(transaction)
    }
      .orElse(getTransaction)
  }

  private val negotiatingDelivery = {
    Actions().onReadOnlyCommand[StartTransaction, Done] {
      case (StartTransaction(_), ctx, _) => ctx.reply(Done)
    }.onCommand[SubmitDeliveryDetails, Done] {
      case (SubmitDeliveryDetails(userId, deliveryData), ctx, state) =>
        if (userId == state.transaction.get.winner) {
          ctx.thenPersist(DeliveryDetailsSubmitted(entityUUID, deliveryData))(_ => ctx.reply(Done))
        } else {
          throw Forbidden("Only the auction winner can submit delivery details")
        }
    }.onCommand[SetDeliveryPrice, Done] {
      case (SetDeliveryPrice(userId, deliveryPrice), ctx, state) =>
        if (userId == state.transaction.get.creator) {
          ctx.thenPersist(DeliveryPriceUpdated(entityUUID, deliveryPrice))(_ => ctx.reply(Done))
        } else {
          throw Forbidden("Only the item creator can set the delivery price")
        }
    }.onCommand[ApproveDeliveryDetails, Done] {
      case (ApproveDeliveryDetails(userId), ctx, state) =>
        if (userId == state.transaction.get.creator) {
          if (state.transaction.get.deliveryData.isDefined && state.transaction.get.deliveryPrice.isDefined) {
            ctx.thenPersist(DeliveryDetailsApproved(entityUUID))(_ => ctx.reply(Done))
          } else {
            throw Forbidden("Can't approve empty delivery detail")
          }
        } else {
          throw Forbidden("Only the item creator can approve the delivery details")
        }
    }.onEvent {
      case (DeliveryDetailsSubmitted(_, deliveryData), state) =>
        state.updateDeliveryData(deliveryData)
    }.onEvent {
      case (DeliveryPriceUpdated(_, deliveryPrice), state) =>
        state.updateDeliveryPrice(deliveryPrice)
    }.onEvent {
      case (DeliveryDetailsApproved(_), state) =>
        state.withStatus(TransactionStatus.PaymentPending)
    }
      .orElse(getTransaction)
  }

  private val paymentPending = {
    Actions().onCommand[SubmitPaymentDetails, Done] {
      case (SubmitPaymentDetails(userId, payment), ctx, state) =>
        if (userId == state.transaction.get.winner) {
          ctx.thenPersist(PaymentDetailsSubmitted(entityUUID, payment))(_ => ctx.reply(Done))
        } else {
          throw Forbidden("Only the auction winner can submit payment details")
        }
    }.onEvent {
      case (PaymentDetailsSubmitted(_, payment), state) =>
        state.updatePayment(payment).withStatus(TransactionStatus.PaymentSubmitted)

    }.onEvent {
      // FIXME: Ported from Java version. Does this belong here?
      case (DeliveryDetailsApproved(_), state) =>
        state.withStatus(TransactionStatus.PaymentPending)
    }
      .orElse(getTransaction)
  }

  private val paymentSubmitted = {
    Actions().onCommand[SubmitPaymentStatus, Done] {
      case (SubmitPaymentStatus(userId, paymentStatus), ctx, state) =>
        if (userId == state.transaction.get.creator) {
          paymentStatus match {
            case PaymentStatus.Approved =>
              ctx.thenPersist(PaymentApproved(entityUUID))(_ => ctx.reply(Done))
            case PaymentStatus.Rejected =>
              ctx.thenPersist(PaymentRejected(entityUUID))(_ => ctx.reply(Done))
            case _ =>
              throw new IllegalArgumentException("Illegal payment status")
          }
        } else {
          throw Forbidden("Only the item creator can approve or reject payment")
        }
    }.onEvent {
      case (PaymentApproved(_), state) =>
        state.withStatus(TransactionStatus.PaymentConfirmed)
      case (PaymentRejected(_), state) =>
        state.withStatus(TransactionStatus.PaymentPending)
    }
      .orElse(getTransaction)
  }

  private val paymentConfirmed = {
    Actions().orElse(getTransaction)
    // TODO: Complete
  }

  private def entityUUID = UUID.fromString(entityId)

}
