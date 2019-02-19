package com.example.auction.transaction.impl

import com.example.auction.item.api.ItemService
import com.example.auction.transaction.api.TransactionService
import com.lightbend.lagom.scaladsl.api.Descriptor
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaClientComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import com.lightbend.rp.servicediscovery.lagom.scaladsl.LagomServiceLocatorComponents
import com.softwaremill.macwire._
import play.api.libs.ws.ahc.AhcWSComponents

abstract class TransactionApplication(context: LagomApplicationContext) extends LagomApplication(context)
  with AhcWSComponents
  with CassandraPersistenceComponents
  with LagomKafkaClientComponents {

  override lazy val lagomServer = serverFor[TransactionService](wire[TransactionServiceImpl])
  lazy val jsonSerializerRegistry = TransactionSerializerRegistry
  lazy val transactionRepository = wire[TransactionRepository]
  lazy val itemService = serviceClient.implement[ItemService]

  persistentEntityRegistry.register(wire[TransactionEntity])
  readSide.register(wire[TransactionEventProcessor])

}

class TransactionApplicationLoader extends LagomApplicationLoader {

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new TransactionApplication(context) with LagomDevModeComponents

  override def load(context: LagomApplicationContext): LagomApplication =
    new TransactionApplication(context) with LagomServiceLocatorComponents

  override def describeService: Option[Descriptor] = Some(readDescriptor[TransactionService])

}
