package org.tygus.suslik.certification.targets.htt.logic

import org.tygus.suslik.certification.targets.htt.language.Expressions._
import org.tygus.suslik.certification.targets.htt.language.Types.CNatSeqType
import org.tygus.suslik.certification.targets.htt.logic.Sentences.CInductivePredicate

import scala.annotation.tailrec
import scala.collection.immutable.Queue

abstract class Hint {
  val dbName: String
  val numHypotheses: Int
  def pp: String
  def ppResolve(ident: String): String = s"Hint Resolve $ident: $dbName"
}

object Hint {
  private var _hintId: Int = 0
  private def freshHintId: Int = {_hintId += 1; _hintId}

  case class PredicateSetTransitive(pred: CInductivePredicate) extends Hint {
    val dbName: String = "ssl_pred"

    case class Hypothesis(params: Seq[CVar], idx: Int) {
      val name = s"${pred.name}_perm_eq_trans$freshHintId"
      val (before, after) = pred.params.map(_._2).splitAt(idx)
      val s1: CVar = CVar("s_1")
      val s2: CVar = CVar("s_2")
      val params1: Seq[CVar] = before ++ Seq(s1) ++ after.tail
      val params2: Seq[CVar] = before ++ Seq(s2) ++ after.tail
      val args: Seq[CVar] = before ++ after.tail ++ Seq(s1, s2)

      def pp: String = {
        val hyp = s"Hypothesis $name: forall ${args.map(_.pp).mkString(" ")}, perm_eq ${s1.pp} ${s2.pp} -> ${pred.name} ${params1.map(_.pp).mkString(" ")} -> ${pred.name} ${params2.map(_.pp).mkString(" ")}"
        s"$hyp.\n${ppResolve(name)}"
      }
    }

    private val hypotheses: Seq[Hypothesis] = {
      val paramVars = pred.params.map(_._2)
      pred.params.zipWithIndex.filter(_._1._1 == CNatSeqType).map(_._2).map(i => Hypothesis(paramVars, i))
    }

    val numHypotheses: Int = hypotheses.length

    def pp: String = {
      s"${hypotheses.map(_.pp).mkString(".\n")}"
    }
  }

  case class PureEntailment(prePhi: Set[CExpr], postPhi: Set[CExpr]) extends Hint {
    val dbName: String = "ssl_pure"
    case class Hypothesis(args: Set[CVar], ctx: Set[CExpr], goal: CExpr) {
      val name = s"pure$freshHintId"
      def pp: String = {
        val ctxStr = ctx.map(_.pp).mkString(" -> ")
        val goalStr = goal.pp
        val hypStr = if (ctx.isEmpty) goalStr else s"$ctxStr -> $goalStr"
        val quantifyStr = if (args.isEmpty) "" else s"forall ${args.map(_.pp).mkString(" ")}, "
        s"Hypothesis $name : $quantifyStr$hypStr.\n${ppResolve(name)}"
      }
    }
    private type ReachableMap = Map[CExpr, Set[CExpr]]
    private type NeighborMap = Map[CExpr, Set[CExpr]]
    private type VarMap = Map[CExpr, Set[CVar]]

    private def reachableConjuncts(m: NeighborMap): ReachableMap = {
      m.keySet.foldLeft[ReachableMap](Map.empty){ case (reachable, e) => reachable + (e -> reachableFromSrc(e, m, reachable))}
    }

    private def reachableFromSrc(src: CExpr, m: NeighborMap, reachable: ReachableMap): Set[CExpr] = {
      @tailrec
      def bfs(curr: CExpr, q: Queue[CExpr], visited: Set[CExpr]): Set[CExpr] = {
        val (visited1, q1) = reachable.get(curr) match {
          case Some(r) => (visited ++ r + curr, q)
          case None => (visited + curr, m.getOrElse(curr, Set.empty).diff(visited).foldLeft(q){ case (q, e) => q.enqueue(e)})
        }
        q1.dequeueOption match {
          case None => visited1
          case Some((next, q1)) => bfs(next, q1, visited1)
        }
      }
      bfs(src, Queue.empty, Set.empty) - src
    }

    private val hypotheses: Seq[Hypothesis] = {
      val preConjuncts = prePhi.filterNot({
        case e if e.isCard => true
        case CUnaryExpr(COpNot, CBinaryExpr(COpBoolEq, CVar(_), CPtrConst(0))) => true
        case _ => false
      })
      val preConjunctToVar = preConjuncts.map(c => (c, c.vars.toSet)).toMap
      val neighborMap = preConjunctToVar.map { case (c, vars) =>
        c -> (preConjuncts.filter(c1 => preConjunctToVar(c1).intersect(vars).nonEmpty) - c)
      }
      val reachableMap = reachableConjuncts(neighborMap)
      val postConjuncts = postPhi.filterNot(_.isCard).diff(preConjuncts)
      postConjuncts.toSeq.map(goal => {
        val goalVars = goal.vars.toSet
        val goalNeighbors = preConjuncts.filter(c1 => preConjunctToVar(c1).intersect(goalVars).nonEmpty)
        val relevantPreConjuncts = reachableFromSrc(goal, neighborMap + (goal -> goalNeighbors), reachableMap)
        val vars = preConjunctToVar.filterKeys(relevantPreConjuncts.contains).values.foldLeft(goalVars) { case (s1, s2) => s1 ++ s2}
        Hypothesis(vars, relevantPreConjuncts, goal)
      })
    }

    val numHypotheses: Int = hypotheses.length

    def pp: String = {
      hypotheses.map(_.pp).mkString(".\n")
    }
  }
}
