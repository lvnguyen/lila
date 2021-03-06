package lila.tournament

import akka.actor._
import akka.pattern.pipe
import org.joda.time.DateTime
import scala.concurrent.duration._

import actorApi._

private[tournament] final class Scheduler(api: TournamentApi) extends Actor {

  import Schedule.Freq._
  import Schedule.Speed._

  def receive = {

    case ScheduleNow =>
      TournamentRepo.scheduled.map(_.flatMap(_.schedule)) map ScheduleNowWith.apply pipeTo self

    case ScheduleNowWith(dbScheds) =>

      val rightNow = DateTime.now
      val today = rightNow.withTimeAtStartOfDay
      val lastDayOfMonth = today.dayOfMonth.withMaximumValue
      val lastSundayOfCurrentMonth = lastDayOfMonth.minusDays((lastDayOfMonth.getDayOfWeek + 0) % 7)
      val nextSaturday = today.plusDays((13 - today.getDayOfWeek) % 7)
      val nextHourDate = rightNow plusHours 1
      val nextHour = nextHourDate.getHourOfDay

      def orTomorrow(date: DateTime) = if (date isBefore rightNow) date plusDays 1 else date

      List(
        Schedule(Monthly, Bullet, at(lastSundayOfCurrentMonth, 18, 0)),
        Schedule(Monthly, SuperBlitz, at(lastSundayOfCurrentMonth, 19, 0)),
        Schedule(Monthly, Blitz, at(lastSundayOfCurrentMonth, 20, 0)),
        Schedule(Monthly, Classical, at(lastSundayOfCurrentMonth, 21, 0)),
        Schedule(Weekly, Bullet, at(nextSaturday, 18)),
        Schedule(Weekly, SuperBlitz, at(nextSaturday, 19)),
        Schedule(Weekly, Blitz, at(nextSaturday, 20)),
        Schedule(Weekly, Classical, at(nextSaturday, 21)),
        Schedule(Daily, Bullet, at(today, 18) |> orTomorrow),
        Schedule(Daily, SuperBlitz, at(today, 19) |> orTomorrow),
        Schedule(Daily, Blitz, at(today, 20) |> orTomorrow),
        Schedule(Daily, Classical, at(today, 21) |> orTomorrow),
        Schedule(Nightly, Bullet, at(today, 6) |> orTomorrow),
        Schedule(Nightly, SuperBlitz, at(today, 7) |> orTomorrow),
        Schedule(Nightly, Blitz, at(today, 8) |> orTomorrow),
        Schedule(Nightly, Classical, at(today, 9) |> orTomorrow),
        Schedule(Hourly, Bullet, at(nextHourDate, nextHour)),
        Schedule(Hourly, SuperBlitz, at(nextHourDate, nextHour)),
        Schedule(Hourly, Blitz, at(nextHourDate, nextHour))
      ).foldLeft(List[Schedule]()) {
          case (scheds, sched) if sched.at.isBeforeNow      => scheds
          case (scheds, sched) if overlaps(sched, dbScheds) => scheds
          case (scheds, sched) if overlaps(sched, scheds)   => scheds
          case (scheds, sched)                              => sched :: scheds
        } foreach api.createScheduled
  }

  private case class ScheduleNowWith(dbScheds: List[Schedule])

  private def endsAt(s: Schedule) = s.at plus ((~Schedule.durationFor(s)).toLong * 60 * 1000)
  private def interval(s: Schedule) = new org.joda.time.Interval(s.at, endsAt(s))
  private def overlaps(s: Schedule, ss: Seq[Schedule]) = ss exists {
    case s2 if s sameSpeed s2 => interval(s) overlaps interval(s2)
    case _                    => false
  }

  private def at(day: DateTime, hour: Int, minute: Int = 0) =
    day withHourOfDay hour withMinuteOfHour minute withSecondOfMinute 0 withMillisOfSecond 0
}
