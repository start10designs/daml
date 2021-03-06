// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger.dao

import java.util.UUID

import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.functor._
import com.daml.daml_lf_dev.DamlLf
import com.daml.lf.archive.Dar
import com.daml.lf.data.Ref.{Identifier, PackageId}
import com.daml.lf.engine.trigger.{EncryptedToken, JdbcConfig, RunningTrigger, UserCredentials}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.log
import doobie.{LogHandler, Transactor, _}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object Connection {

  type T = Transactor.Aux[IO, Unit]

  def connect(jdbcDriver: String, jdbcUrl: String, username: String, password: String)(
      implicit cs: ContextShift[IO]): T =
    Transactor
      .fromDriverManager[IO](jdbcDriver, jdbcUrl, username, password)(IO.ioConcurrentEffect(cs), cs)
}

class DbTriggerDao(xa: Connection.T) extends RunningTriggerDao {

  private val logHandler: log.LogHandler = doobie.util.log.LogHandler.jdkLogHandler

  private def createTables(implicit logHandler: LogHandler): ConnectionIO[Unit] = {
    // Running trigger table.
    // `trigger_instance` is a UUID generated by the service
    // `party_token` is the token corresponding to the party
    // `full_trigger_name` is the identifier for the trigger in its dalf,
    //  of the form "packageId:moduleName:triggerName"
    val createTriggerTable: Fragment = sql"""
        create table running_triggers(
          trigger_instance uuid primary key,
          party_token text not null,
          full_trigger_name text not null
        )
      """

    // Dalf table with binary package data.
    val createDalfTable: Fragment = sql"""
        create table dalfs(
          package_id text primary key,
          package bytea not null
        )
      """

    // Index for efficiently listing running triggers for a particular party.
    val createPartyIndex: Fragment = sql"""
        create index triggers_by_party on running_triggers(party_token)
      """

    (createTriggerTable.update.run
      *> createDalfTable.update.run
      *> createPartyIndex.update.run).void
  }

  // NOTE(RJR) Interpolation in `sql` literals:
  // Doobie provides a `Put` typeclass that allows us to interpolate values of various types in our
  // SQL query strings. This includes basic types like `String` and `UUID` as well as unary case
  // classes wrapping these types (e.g. `EncryptedToken`). Doobie also does some formatting of these
  // values, e.g. single quotes around `String` and `UUID` values. This is NOT the case if you use
  // `Fragment.const` which will try to use a raw string as a SQL query.

  private def insertRunningTrigger(t: RunningTrigger): ConnectionIO[Unit] = {
    val partyToken: EncryptedToken = t.credentials.token
    val fullTriggerName: String = t.triggerName.toString
    val insert: Fragment = sql"""
        insert into running_triggers values (${t.triggerInstance}, $partyToken, $fullTriggerName)
      """
    insert.update.run.void
  }

  // trigger_instance is the primary key on running_triggers so this deletes
  // at most one row. Return whether or not it deleted.
  private def deleteRunningTrigger(triggerInstance: UUID): ConnectionIO[Boolean] = {
    val delete = sql"delete from running_triggers where trigger_instance = $triggerInstance"
    delete.update.run.map(_ == 1)
  }

  private def selectRunningTriggers(partyToken: EncryptedToken): ConnectionIO[Vector[UUID]] = {
    val select: Fragment = sql"""
        select trigger_instance from running_triggers
        where party_token = $partyToken
      """
    // We do not use an `order by` clause because we sort the UUIDs afterwards using Scala's
    // comparison of UUIDs (which is different to Postgres).
    select.query[UUID].to[Vector]
  }

  // Insert a package to the `dalfs` table. Do nothing if the package already exists.
  // We specify this in the `insert` since `packageId` is the primary key on the table.
  private def insertPackage(
      packageId: PackageId,
      pkg: DamlLf.ArchivePayload): ConnectionIO[Unit] = {
    val insert: Fragment = sql"""
      insert into dalfs values (${packageId.toString}, ${pkg.toByteArray}) on conflict do nothing
    """
    insert.update.run.void
  }

  private def selectPackages: ConnectionIO[List[(String, Array[Byte])]] = {
    val select: Fragment = sql"select * from dalfs order by package_id"
    select.query[(String, Array[Byte])].to[List]
  }

  private def parsePackage(
      pkgIdString: String,
      pkgPayload: Array[Byte]): Either[String, (PackageId, DamlLf.ArchivePayload)] =
    for {
      pkgId <- PackageId.fromString(pkgIdString)
      payload <- Try(DamlLf.ArchivePayload.parseFrom(pkgPayload)) match {
        case Failure(err) => Left(s"Failed to parse package with id $pkgId.\n" ++ err.toString)
        case Success(pkg) => Right(pkg)
      }
    } yield (pkgId, payload)

  private def selectAllTriggers: ConnectionIO[Vector[(UUID, String, String)]] = {
    val select: Fragment = sql"select * from running_triggers order by trigger_instance"
    select.query[(UUID, String, String)].to[Vector]
  }

  private def parseRunningTrigger(
      triggerInstance: UUID,
      token: String,
      fullTriggerName: String): Either[String, RunningTrigger] = {
    val credentials = UserCredentials(EncryptedToken(token))
    Identifier.fromString(fullTriggerName).map(RunningTrigger(triggerInstance, _, credentials))
  }

  // Drop all tables and other objects associated with the database.
  // Only used between tests for now.
  private def dropTables: ConnectionIO[Unit] = {
    val dropTriggerTable: Fragment = sql"drop table running_triggers"
    val dropDalfTable: Fragment = sql"drop table dalfs"
    (dropTriggerTable.update.run
      *> dropDalfTable.update.run).void
  }

  private def run[T](query: ConnectionIO[T], errorContext: String = ""): Either[String, T] = {
    Try(query.transact(xa).unsafeRunSync) match {
      case Failure(err) => Left(errorContext ++ "\n" ++ err.toString)
      case Success(res) => Right(res)
    }
  }

  override def addRunningTrigger(t: RunningTrigger): Either[String, Unit] =
    run(insertRunningTrigger(t))

  override def removeRunningTrigger(triggerInstance: UUID): Either[String, Boolean] =
    run(deleteRunningTrigger(triggerInstance))

  override def listRunningTriggers(credentials: UserCredentials): Either[String, Vector[UUID]] = {
    // Note(RJR): Postgres' ordering of UUIDs is different to Scala/Java's.
    // We sort them after the query to be consistent with the ordering when not using a database.
    run(selectRunningTriggers(credentials.token)).map(_.sorted)
  }

  // Write packages to the `dalfs` table so we can recover state after a shutdown.
  override def persistPackages(
      dar: Dar[(PackageId, DamlLf.ArchivePayload)]): Either[String, Unit] = {
    import cats.implicits._ // needed for traverse
    val insertAll = dar.all.traverse_((insertPackage _).tupled)
    run(insertAll)
  }

  def readPackages: Either[String, List[(PackageId, DamlLf.ArchivePayload)]] = {
    import cats.implicits._ // needed for traverse
    run(selectPackages, "Failed to read packages from database").flatMap(
      _.traverse((parsePackage _).tupled)
    )
  }

  def readRunningTriggers: Either[String, Vector[RunningTrigger]] = {
    import cats.implicits._ // needed for traverse
    run(selectAllTriggers, "Failed to read running triggers from database").flatMap(
      _.traverse((parseRunningTrigger _).tupled)
    )
  }

  def initialize: Either[String, Unit] =
    run(createTables(logHandler), "Failed to initialize database.")

  def destroy: Either[String, Unit] =
    run(dropTables, "Failed to remove database objects.")
}

object DbTriggerDao {

  def apply(c: JdbcConfig)(implicit ec: ExecutionContext): DbTriggerDao = {
    val cs: ContextShift[IO] = IO.contextShift(ec)
    val conn: Connection.T = Connection.connect(JdbcConfig.driver, c.url, c.user, c.password)(cs)
    new DbTriggerDao(conn)
  }
}
