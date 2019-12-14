package lila.tv

import scala.concurrent.duration._
import scala.concurrent.Promise

import chess.Color
import lila.game.Game
import lila.hub.Trouper

final private[tv] class ChannelTrouper(
    channel: Tv.Channel,
    onSelect: TvTrouper.Selected => Unit,
    proxyGame: Game.ID => Fu[Option[Game]],
    rematchOf: Game.ID => Option[Game.ID]
)(implicit ec: scala.concurrent.ExecutionContext)
    extends Trouper {

  import ChannelTrouper._

  // games featured on this channel
  // first entry is the current game
  private var history = List.empty[Game.ID]

  private def oneId = history.headOption

  // the list of candidates by descending rating order
  private var manyIds = List.empty[Game.ID]

  private val candidateIds = new lila.memo.ExpireSetMemo(3 minutes)

  protected val process: Trouper.Receive = {

    case GetGameId(promise) => promise success oneId

    case GetGameIdAndHistory(promise) => promise success GameIdAndHistory(oneId, history drop 1)

    case GetGameIds(max, promise) => promise success manyIds.take(max)

    case SetGame(game) =>
      onSelect(TvTrouper.Selected(channel, game))
      history = game.id :: history.take(2)

    case TvTrouper.Select =>
      candidateIds.keys
        .map(proxyGame)
        .sequenceFu
        .map(_.view.collect {
          case Some(g) if channel isFresh g => g
        }.toList)
        .foreach { candidates =>
          oneId ?? proxyGame foreach {
            case Some(current) if channel isFresh current =>
              fuccess(wayBetter(current, candidates)) orElse rematch(current) foreach elect
            case Some(current) => rematch(current) orElse fuccess(bestOf(candidates)) foreach elect
            case _             => elect(bestOf(candidates))
          }
          manyIds = candidates
            .sortBy { g =>
              -(~g.averageUsersRating)
            }
            .take(50)
            .map(_.id)
        }
  }

  def addCandidate(game: Game): Unit = candidateIds put game.id

  private def elect(gameOption: Option[Game]): Unit = gameOption foreach { this ! SetGame(_) }

  private def wayBetter(game: Game, candidates: List[Game]) =
    bestOf(candidates) filter { isWayBetter(game, _) }

  private def isWayBetter(g1: Game, g2: Game) = score(g2.resetTurns) > (score(g1.resetTurns) * 1.17)

  private def rematch(game: Game): Fu[Option[Game]] = rematchOf(game.id) ?? proxyGame

  private def bestOf(candidates: List[Game]) =
    candidates sortBy { -score(_) } headOption

  private def score(game: Game): Int = math.round {
    (heuristics map {
      case (fn, coefficient) => heuristicBox(fn(game)) * coefficient
    }).sum * 1000
  }

  private type Heuristic = Game => Float
  private val heuristicBox = box(0 to 1) _
  private val ratingBox    = box(1000 to 2700) _
  private val turnBox      = box(1 to 25) _

  private val heuristics: List[(Heuristic, Float)] = List(
    ratingHeuristic(Color.White) -> 1.2f,
    ratingHeuristic(Color.Black) -> 1.2f,
    progressHeuristic            -> 0.7f
  )

  private def ratingHeuristic(color: Color): Heuristic = game => ratingBox(game.player(color).rating | 1400)

  private def progressHeuristic: Heuristic = game => 1 - turnBox(game.turns)

  // boxes and reduces to 0..1 range
  private def box(in: Range.Inclusive)(v: Float): Float =
    (math.max(in.start, math.min(v, in.end)) - in.start) / (in.end - in.start).toFloat
}

object ChannelTrouper {

  case class GetGameId(promise: Promise[Option[Game.ID]])
  case class GetGameIds(max: Int, promise: Promise[List[Game.ID]])
  private case class SetGame(game: Game)

  case class GetGameIdAndHistory(promise: Promise[GameIdAndHistory])
  case class GameIdAndHistory(gameId: Option[Game.ID], history: List[Game.ID])
}
