package com.example.auction.transaction.impl

import java.util.UUID

import akka.Done
import com.datastax.driver.core.{BoundStatement, PreparedStatement, Row}
import com.example.auction.transaction.api.{TransactionInfoStatus, TransactionSummary}
import com.example.auction.utils.PaginatedSequence
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}

import scala.concurrent.{ExecutionContext, Future, Promise}

class TransactionRepository(session: CassandraSession)(implicit ec: ExecutionContext) {

  def getTransactionsForUser(userId: UUID, status: TransactionInfoStatus.Status, page: Int, pageSize: Int): Future[PaginatedSequence[TransactionSummary]] = {
    for {
      count <- countUserTransactions(userId, status)
      transactions <- {
        val offset = page * pageSize
        val limit = (page + 1) * pageSize
        if (offset > count) Future.successful(Seq.empty[TransactionSummary])
        else selectUserTransactions(userId, status, offset, limit)
      }
    } yield {
      PaginatedSequence(transactions, page, pageSize, count)
    }
  }

  private def countUserTransactions(userId: UUID, status: TransactionInfoStatus.Status): Future[Int] = {
    session.selectOne(
      """SELECT COUNT(*)
        |FROM transactionSummaryByUserAndStatus
        |WHERE userId = ? AND status = ?
        |ORDER BY status ASC, itemId DESC""".stripMargin,
      userId, status.toString).map {
      case Some(row) => row.getLong("count").toInt
      case None => 0
    }
  }

  private def selectUserTransactions(userId: UUID, status: TransactionInfoStatus.Status, offset: Int, limit: Int): Future[Seq[TransactionSummary]] = {
    session.selectAll(
      """SELECT * FROM transactionSummaryByUserAndStatus
        |WHERE userId = ? AND status = ?
        |ORDER BY status ASC, itemId DESC
        |LIMIT ?
      """.stripMargin,
      userId, status.toString, Int.box(limit)).map { rows =>
      rows.drop(offset).map(toTransactionSummary)
    }
  }

  private def toTransactionSummary(transaction: Row): TransactionSummary = {
    TransactionSummary(
      transaction.getUUID("itemId"),
      transaction.getUUID("creatorId"),
      transaction.getUUID("winnerId"),
      transaction.getString("itemTitle"),
      transaction.getString("currencyId"),
      transaction.getInt("itemPrice"),
      TransactionInfoStatus.withName(transaction.getString("status"))
    )
  }

}

class TransactionEventProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
  extends ReadSideProcessor[TransactionEvent] {

  private val insertTransactionUserStatementPromise = Promise[PreparedStatement]

  private def insertTransactionUserStatement = insertTransactionUserStatementPromise.future

  private val insertTransactionSummaryByUserStatementPromise = Promise[PreparedStatement]

  private def insertTransactionSummaryByUserStatement = insertTransactionSummaryByUserStatementPromise.future

  private val updateTransactionSummaryStatusStatementPromise = Promise[PreparedStatement]

  private def updateTransactionSummaryStatusStatement = updateTransactionSummaryStatusStatementPromise.future

  override def buildHandler() = {
    readSide.builder[TransactionEvent]("transactionEventOffset")
      .setGlobalPrepare(createTables)
      .setPrepare(_ => prepareStatements())
      .setEventHandler[TransactionStarted](e => insertTransaction(e.event.itemId, e.event.transaction))
      .setEventHandler[DeliveryDetailsApproved](e => updateTransactionSummaryStatus(e.event.itemId, TransactionInfoStatus.PaymentPending))
      .setEventHandler[PaymentDetailsSubmitted](e => updateTransactionSummaryStatus(e.event.itemId, TransactionInfoStatus.PaymentSubmitted))
      .setEventHandler[PaymentApproved](e => updateTransactionSummaryStatus(e.event.itemId, TransactionInfoStatus.PaymentConfirmed))
      .setEventHandler[PaymentRejected](e => updateTransactionSummaryStatus(e.event.itemId, TransactionInfoStatus.PaymentPending))
      .build()
  }

  override def aggregateTags = TransactionEvent.Tag.allTags

  private def createTables() = {
    for {
      _ <- session.executeCreateTable(
        """CREATE TABLE IF NOT EXISTS transactionUsers (
          |  itemId timeuuid PRIMARY KEY,
          |  creatorId UUID,
          |  winnerId UUID
          |)""".stripMargin)
      _ <- session.executeCreateTable(
        """CREATE TABLE IF NOT EXISTS transactionSummaryByUser (
          |  userId UUID,
          |  itemId timeuuid,
          |  creatorId UUID,
          |  winnerId UUID,
          |  itemTitle text,
          |  currencyId text,
          |  itemPrice int,
          |  status text,
          |  PRIMARY KEY (userId, itemId)
          |)
          |WITH CLUSTERING ORDER BY (itemId DESC)""".stripMargin)
      _ <- session.executeCreateTable(
        """CREATE MATERIALIZED VIEW IF NOT EXISTS transactionSummaryByUserAndStatus AS
          |  SELECT * FROM transactionSummaryByUser
          |  WHERE status IS NOT NULL AND itemId IS NOT NULL
          |PRIMARY KEY (userId, status, itemId)
          |WITH CLUSTERING ORDER BY (status ASC, itemId DESC)""".stripMargin)
    } yield Done
  }

  private def prepareStatements() = {

    val prepareInsertTransactionUserStatement = session.prepare(
      """INSERT INTO transactionUsers (
        |  itemId, creatorId, winnerId)
        |VALUES (?, ?, ?)""".stripMargin)
    insertTransactionUserStatementPromise.completeWith(prepareInsertTransactionUserStatement)

    val prepareInsertTransactionSummaryByUserStatement = session.prepare(
      """INSERT INTO transactionSummaryByUser(
        |  userId,
        |  itemId,
        |  creatorId,
        |  winnerId,
        |  itemTitle,
        |  currencyId,
        |  itemPrice,
        |  status
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)
    insertTransactionSummaryByUserStatementPromise.completeWith(prepareInsertTransactionSummaryByUserStatement)

    val prepareUpdateTransactionSummaryStatusStatement = session.prepare(
      """UPDATE transactionSummaryByUser
        |SET status = ?
        |WHERE userId = ? AND itemId = ?""".stripMargin)
    updateTransactionSummaryStatusStatementPromise.completeWith(prepareUpdateTransactionSummaryStatusStatement)

    for {
      _ <- prepareInsertTransactionUserStatement
      _ <- prepareInsertTransactionSummaryByUserStatement
      _ <- prepareUpdateTransactionSummaryStatusStatement
    } yield Done

  }

  private def insertTransaction(itemId: UUID, transaction: Transaction) = {
    for {
      insertUser <- insertTransactionUser(itemId, transaction.creator, transaction.winner)
      creatorSummary <- insertTransactionSummaryByUser(itemId, transaction.creator, transaction)
      winnerSummary <- insertTransactionSummaryByUser(itemId, transaction.winner, transaction)
    } yield {
      List(insertUser, creatorSummary, winnerSummary)
    }
  }

  private def insertTransactionUser(itemId: UUID, creatorId: UUID, winnerId: UUID) = {
    insertTransactionUserStatement.map { ps =>
      val binding = ps.bind()
      binding.setUUID("itemId", itemId)
      binding.setUUID("creatorId", creatorId)
      binding.setUUID("winnerId", winnerId)
      binding
    }
  }

  private def insertTransactionSummaryByUser(itemId: UUID, userId: UUID, transaction: Transaction) = {
    insertTransactionSummaryByUserStatement.map { ps =>
      val binding = ps.bind()
      binding.setUUID("userId", userId)
      binding.setUUID("itemId", itemId)
      binding.setUUID("creatorId", transaction.creator)
      binding.setUUID("winnerId", transaction.winner)
      binding.setString("itemTitle", transaction.itemData.title)
      binding.setString("currencyId", transaction.itemData.currencyId)
      binding.setInt("itemPrice", transaction.itemPrice)
      binding.setString("status", TransactionInfoStatus.NegotiatingDelivery.toString)
      binding
    }
  }

  private def updateTransactionSummaryStatus(status: TransactionInfoStatus.Status, creatorId: UUID, itemId: UUID): Future[BoundStatement] = {
    updateTransactionSummaryStatusStatement.map { ps =>
      val binding = ps.bind()
      binding.setString("status", status.toString)
      binding.setUUID("userId", creatorId)
      binding.setUUID("itemId", itemId)
      binding
    }
  }

  private def selectTransactionUser(itemId: UUID) = {
    session.selectOne("SELECT * FROM transactionUsers WHERE itemId = ?", itemId)
  }

  private def updateTransactionSummaryStatus(itemId: UUID, status: TransactionInfoStatus.Status): Future[List[BoundStatement]] = {
    selectTransactionUser(itemId).flatMap {
      case None => throw new IllegalStateException(s"No transactionUsers found for itemId $itemId")
      case Some(row) =>
        val creatorId = row.getUUID("creatorId")
        val winnerId = row.getUUID("winnerId")
        for {
          updateCreatorStatus <- updateTransactionSummaryStatus(status, creatorId, itemId)
          updateWinnerStatus <- updateTransactionSummaryStatus(status, winnerId, itemId)
        } yield {
          List(updateCreatorStatus, updateWinnerStatus)
        }
    }
  }

}
