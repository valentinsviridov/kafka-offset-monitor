package com.quantifind.kafka.offsetapp

import scala.slick.driver.SQLiteDriver.simple._
import scala.slick.jdbc.{JdbcBackend, JdbcType, StaticQuery}
import scala.slick.jdbc.meta.MTable
import com.quantifind.kafka.OffsetGetter.KafkaOffsetInfo
import com.quantifind.kafka.offsetapp.OffsetDB.{DbOffsetInfo, OffsetHistory, OffsetPoints}
import com.twitter.util.Time

import scala.slick.ast.BaseTypedType
import scala.slick.driver.SQLiteDriver
import scala.slick.lifted.{Index, MappedProjection, ProvenShape}

/**
  * Tools to store offsets in a DB
  * User: andrews
  * Date: 1/27/14
  */
class OffsetDB(dbfile: String) {

  val database: SQLiteDriver.backend.DatabaseDef = Database.forURL(s"jdbc:sqlite:$dbfile.db", driver = "org.sqlite.JDBC")

  implicit val twitterTimeMap: JdbcType[Time] with BaseTypedType[Time] = MappedColumnType.base[Time, Long](
    {
      time => time.inMillis
    }, {
      millis => Time.fromMilliseconds(millis)
    }
  )

  class Offset(tag: Tag) extends Table[DbOffsetInfo](tag, "OFFSETS") {
    def id: Column[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)

    val group: Column[String] = column[String]("group")
    val topic: Column[String] = column[String]("topic")
    val partition: Column[Int] = column[Int]("partition")
    val offset: Column[Long] = column[Long]("offset")
    val logSize: Column[Long] = column[Long]("log_size")
    val owner: Column[Option[String]] = column[Option[String]]("owner")
    val timestamp: Column[Long] = column[Long]("timestamp")
    val creation: Column[Time] = column[Time]("creation")
    val modified: Column[Time] = column[Time]("modified")

    def * : ProvenShape[DbOffsetInfo] = (id.?, group, topic, partition, offset, logSize, owner, timestamp, creation, modified).shaped <> (DbOffsetInfo.parse, DbOffsetInfo.unparse)

    def forHistory: MappedProjection[OffsetPoints, (Long, Int, Option[String], Long, Long)] = (timestamp, partition, owner, offset, logSize) <> (OffsetPoints.tupled, OffsetPoints.unapply)

    def idx: Index = index("idx_search", (group, topic))

    def tidx: Index = {
      index("idx_time", timestamp)
    }

    def uidx: Index = index("idx_unique", (group, topic, partition, timestamp), unique = true)

  }

  val offsets: TableQuery[Offset] = TableQuery[Offset]

  def insert(timestamp: Long, info: KafkaOffsetInfo) {
    database.withSession {
      implicit s =>
        offsets += DbOffsetInfo(timestamp = timestamp, offset = info)
    }
  }

  def insertAll(info: IndexedSeq[KafkaOffsetInfo]) {
    val now = System.currentTimeMillis
    database.withTransaction {
      implicit s =>
        offsets ++= info.map(i => DbOffsetInfo(timestamp = now, offset = i))
    }
  }

  def emptyOld(since: Long) {
    database.withSession {
      implicit s =>
        offsets.filter(_.timestamp < since).delete
        StaticQuery.updateNA("VACUUM").execute
    }
  }

  def offsetHistory(group: String, topic: String): OffsetHistory = database.withSession {
    implicit s =>
      val o = offsets
        .filter(off => off.group === group && off.topic === topic)
        .sortBy(_.timestamp)
        .map(_.forHistory)
        .list(implicitly[JdbcBackend#Session])
      OffsetHistory(group, topic, o)
  }

  def maybeCreate() {
    database.withSession {
      implicit s =>
        if (MTable.getTables("OFFSETS").list(implicitly[JdbcBackend#Session]).isEmpty) {
          offsets.ddl.create
        }
    }
  }

}

object OffsetDB {

  case class OffsetPoints(timestamp: Long, partition: Int, owner: Option[String], offset: Long, logSize: Long)

  case class OffsetHistory(group: String, topic: String, offsets: Seq[OffsetPoints])

  case class DbOffsetInfo(id: Option[Int] = None, timestamp: Long, offset: KafkaOffsetInfo)

  object DbOffsetInfo {
    def parse(in: (Option[Int], String, String, Int, Long, Long, Option[String], Long, Time, Time)): DbOffsetInfo = {
      val (id, group, topic, partition, offset, logSize, owner, timestamp, creation, modified) = in
      DbOffsetInfo(id, timestamp, KafkaOffsetInfo(group, topic, partition, offset, logSize, owner, creation, modified))
    }

    def unparse(in: DbOffsetInfo): Option[(Option[Int], String, String, Int, Long, Long, Option[String], Long, Time, Time)] = Some(
      in.id,
      in.offset.group,
      in.offset.topic,
      in.offset.partition,
      in.offset.offset,
      in.offset.logSize,
      in.offset.owner,
      in.timestamp,
      in.offset.creation,
      in.offset.modified
    )
  }

}
