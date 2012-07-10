package lila
package analyse

import com.codahale.jerkson.Json

final class AdvantageChart(advices: Analysis.InfoAdvices) {

  val max = 10

  def columns = AdvantageChart.columns

  def rows = Json generate chartValues

  private lazy val values: List[(String, Float)] =
    (advices sliding 2 collect {
      case (info, advice) :: (next, _) :: Nil ⇒
        (next.score, next.mate) match {
          case (Some(score), _) ⇒ move(info, advice) -> box(score.pawns)
          case (_, Some(mate))  ⇒ move(info, advice) -> {
            val mateDelta = math.abs(mate.toFloat / 100)
            val whiteWins = info.color.fold(mate < 0, mate > 0)
            box(whiteWins.fold(max - mateDelta, mateDelta - max))
          }
          case _                ⇒ move(info, none) -> box(0)
        }
    }).toList

  private def chartValues: List[List[Any]] = values map {
    case (move, score) ⇒ List(move, score)
  }

  private def box(v: Float) = math.min(max, math.max(-max, v))

  private def move(info: Info, advice: Option[Advice]) = info.color.fold(
    "%d. %s", "%d... %s"
  ).format(info.turn, info.move.uci) + advice.fold(" " + _.nag.symbol, "")

}

object AdvantageChart {

  val columns = Json generate List(
    "string" :: "Move" :: Nil,
    "number" :: "Advantage" :: Nil)
}
