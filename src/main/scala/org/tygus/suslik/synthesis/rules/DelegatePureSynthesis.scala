package org.tygus.suslik.synthesis.rules


import org.bitbucket.franck44.scalasmt.parser.SMTLIB2Parser
import org.bitbucket.franck44.scalasmt.parser.SMTLIB2Syntax._
import org.bitbucket.inkytonik.kiama.util.StringSource
import org.tygus.suslik.language.Expressions.{BinaryExpr, IntConst, SetLiteral, Subst}
import org.tygus.suslik.language._
import org.tygus.suslik.logic.Specifications.{Assertion, Goal}
import org.tygus.suslik.logic.{PFormula, Specifications}
import org.tygus.suslik.synthesis.rules.DelegatePureSynthesis.TaskType.TaskType
import org.tygus.suslik.synthesis.rules.Rules.{InvertibleRule, RuleResult, SynthesisRule}
import org.tygus.suslik.synthesis.{ExistentialProducer, ExtractHelper, HandleGuard, IdProducer}

import scala.sys.process._
import scala.util.{Failure, Success}

object DelegatePureSynthesis {

  def typeToSMT(lType: SSLType): String = lType match {
    case IntType | LocType | CardType => "Int"
    case BoolType => "Bool"
    case IntSetType => "(Set Int)"
  }

  val empsetName = "empset"
  val typeConstants: Map[SSLType, List[String]] = Map(
    IntType -> List("0"), LocType -> List("0"), IntSetType -> List(empsetName), CardType -> List("0")
  )
  val condName = "cond"

  def toSmtExpr(c: Expressions.Expr, existentials: Map[Expressions.Var, String], sb: StringBuilder): Unit = c match {
    case v: Expressions.Var => sb ++= (if (existentials contains v) existentials(v) else v.name)
    case const: Expressions.Const => sb ++= const.pp
    case SetLiteral(elems) => elems.length match {
      case 0 => sb ++= empsetName
      case 1 =>
        sb ++= "(singleton "
        toSmtExpr(elems.head, existentials, sb)
        sb ++= ")"
      case _ =>
        sb ++= "(insert "
        for (e <- elems.dropRight(1)) {
          toSmtExpr(e, existentials, sb)
          sb ++= " "
        }
        sb ++= "(singleton "
        toSmtExpr(elems.last, existentials, sb)
        sb ++= "))"
    }
    case Expressions.BinaryExpr(op, left, right) => sb ++= "(" ++= (op match {
      case Expressions.OpAnd => "and"
      case Expressions.OpOr => "or"
      case Expressions.OpImplication => "=>"
      case Expressions.OpPlus => "+"
      case Expressions.OpMinus => "-"
      case Expressions.OpMultiply => "*"
      case Expressions.OpEq => "="
      case Expressions.OpBoolEq | Expressions.OpSetEq => "="
      case Expressions.OpLeq => "<="
      case Expressions.OpLt => "<"
      //Set ops all come from here: https://cvc4.github.io/sets-and-relations
      case Expressions.OpIn => "member"
      case Expressions.OpSubset => "subset"
      case Expressions.OpUnion => "union"
      case Expressions.OpDiff => "setminus"
      case Expressions.OpIntersect => "intersection"
    }) ++= " "
      toSmtExpr(left, existentials, sb)
      sb ++= " "
      toSmtExpr(right, existentials, sb)
      sb ++= ")"
    //case Expressions.OverloadedBinaryExpr(overloaded_op, left, right) =>
    case Expressions.UnaryExpr(op, arg) => sb ++= "(" ++= (op match {
      case Expressions.OpNot => "not"
      case Expressions.OpUnaryMinus => "-"
    }) ++= " "
      toSmtExpr(arg, existentials, sb)
      sb ++= ")"
    case Expressions.IfThenElse(cond, left, right) =>
      sb ++= "(ite "
      toSmtExpr(cond, existentials, sb)
      sb ++= " "
      toSmtExpr(left, existentials, sb)
      sb ++= " "
      toSmtExpr(right, existentials, sb)
      sb ++= ")"
  }

  def mkExistentialCalls(existentials: Set[Expressions.Var], otherVars: List[(Expressions.Var, SSLType)]): Map[Expressions.Var, String] =
    existentials.map { ex =>
      (ex, "(target_" + ex.name + (for (v <- otherVars) yield v._1.name).mkString(" ", " ", "") + ")")
    }.toMap

  def toSmt(phi: PFormula, existentials: Map[Expressions.Var, String], sb: StringBuilder): Unit = phi.conjuncts.size match {
    case 0 => sb ++= "true"
    case 1 => toSmtExpr(phi.conjuncts.head, existentials, sb)
    case _ => sb ++= "(and "
      for (c <- phi.conjuncts) {
        toSmtExpr(c, existentials, sb)
        sb ++= " "
      }
      sb ++= ")"
  }

  def usesEmptyset(a: Specifications.Assertion): Boolean = a.phi.conjuncts.exists(e => !e.collect(expr =>
    expr.isInstanceOf[Expressions.SetLiteral] && expr.asInstanceOf[Expressions.SetLiteral].elems.isEmpty).isEmpty)

  def makeSynthesisTarget(targetName: String, etypeOpt: Option[SSLType], otherVars: List[(Expressions.Var,SSLType)], grammarExclusion: Option[Expressions.Expr], taskType: TaskType, sb: StringBuilder): Unit = {
    val etypeStr = typeToSMT(etypeOpt.get)
    sb ++= "(synth-fun target_" ++= targetName ++= " ("
    for (v <- otherVars)
      sb ++= "(" ++= v._1.name ++= " " ++= typeToSMT(v._2) ++= ") "
    sb ++= ") " ++= etypeStr ++= "\n"
    sb ++= "  ((Start " ++= etypeStr ++= " ("
    if (taskType == TaskType.existentials) {
      for (c <- typeConstants(etypeOpt.get))
        sb ++= c ++= " "
      for (v <- otherVars; if grammarExclusion.map(a => v._1 != a).getOrElse(true); if v._2.conformsTo(etypeOpt))
        sb ++= v._1.name ++= " "
    }
    else {
      sb ++= "flatBool conjBool))\n   (nInt Int ("
      for (v <- otherVars; if grammarExclusion.map(a => v._1 != a).getOrElse(true); if v._2.conformsTo(Some(IntType)))
        sb ++= v._1.name ++= " "
      sb ++= "))\n   (flatBool Bool ((<= nInt nInt)))\n   (conjBool Bool ((and flatBool flatBool)"
    }
    sb ++= ")))"
    sb ++= ")\n"
  }

  object TaskType extends Enumeration
  {
    type TaskType = Value
    val existentials, condition = Value
  }
  def toSMTTask(goal: Specifications.Goal, grammarExclusion: Option[(Expressions.Var,Expressions.Expr)] = None, taskType: TaskType = TaskType.existentials): String = {
    val sb = new StringBuilder
    sb ++= "(set-logic ALL)\n\n"

    if (goal.gamma.exists { case (v, t) => t == IntSetType && goal.isExistential(v) } || usesEmptyset(goal.post) || usesEmptyset(goal.pre))
      sb ++= s"(define-fun $empsetName () (Set Int) (as emptyset (Set Int)))\n\n"

    val otherVars = (goal.gamma -- goal.existentials).toList
    if (taskType == TaskType.condition) makeSynthesisTarget(condName,Some(BoolType),otherVars,None,taskType,sb)
    else for (ex <- goal.existentials) {
      val etypeOpt = ex.getType(goal.gamma)
      makeSynthesisTarget(ex.name,etypeOpt,otherVars,grammarExclusion.filter(_._1 == ex).map(_._2),taskType,sb)
    }

    sb ++= "\n"
    for (v <- otherVars)
      sb ++= "(declare-var " ++= v._1.name ++= " " ++= typeToSMT(v._2) ++= ")\n"
    sb ++= "\n(constraint\n"
    sb ++= "    (=> "
    if (taskType == TaskType.condition) sb ++= "(and "
    lazy val existentialMap = mkExistentialCalls(goal.existentials, otherVars)
    toSmt(goal.pre.phi, Map.empty, sb) //no existential vars in pre
    if (taskType == TaskType.condition){
      sb ++= s" (target_$condName "
      for (v <- otherVars) sb ++= v._1.name ++= " "
      sb ++= "))"
    }
    sb ++= " "
    toSmt(if (taskType == TaskType.condition) goal.universalPost else goal.post.phi, existentialMap, sb)
    sb ++= "))"
    sb ++= "\n(check-synth)"
    sb.toString
  }

  val cvc4exe = "cvc4"
  val cvc4Cmd = cvc4exe + " --sygus-out=status-or-def --lang sygus" //" --cegqi-si=all --sygus-out=status-or-def --lang sygus"
  def invokeCVC(task: String): Option[String] = { //<-- if we ever get the library compiled, fix it here
    var out: String = null
    val io = BasicIO.standard { ostream =>
      ostream.write(task.getBytes)
      ostream.flush();
      ostream.close()
    }.withOutput { istream =>
      out = scala.io.Source.fromInputStream(istream).mkString
    }
    val cvc4 = cvc4Cmd.run(io)
    if (cvc4.exitValue() != 0) None
    else if (out.trim == "unknown") None //unsynthesizable
    else Some(out)
  }

  private var configured = false

  def isConfigured(): Boolean = this.synchronized {
    configured = try {
      cvc4Cmd.! == 0
    } catch {
      case _: Throwable => false
    }
    configured
  }


  val parser = SMTLIB2Parser[GetModelResponses]

  def parseAssignments(cvc4Res: String): Subst = {
    //〈FunDefCmd〉::=  (define-fun〈Symbol〉((〈Symbol〉 〈SortExpr〉)∗)〈SortExpr〉 〈Term〉
    parser.apply(StringSource("(model " + cvc4Res + ")")) match {
      case Failure(exception) => Map.empty
      case Success(GetModelFunDefResponseSuccess(responses)) =>
        responses.map { response =>
          val existential = response.funDef.sMTLIB2Symbol.asInstanceOf[SSymbol].simpleSymbol.drop(7)
          val expr = response.funDef.term match {
            case QIdTerm(SimpleQId(SymbolId(SSymbol(simpleSymbol)))) => if (simpleSymbol == empsetName) Expressions.SetLiteral(List())
                                                                        else Expressions.Var(simpleSymbol)
            case ConstantTerm(NumLit(numeralLiteral)) => IntConst(numeralLiteral.toInt)
          }
          Expressions.Var(existential) -> expr
        }.toMap
    }
  }

  def parseConditions(cvc4Res: String): Set[Expressions.Expr] = {
    def extractLeq(term: LessThanEqualTerm): Expressions.Expr = term match {
      case LessThanEqualTerm(QIdTerm(SimpleQId(SymbolId(SSymbol(lhs)))),QIdTerm(SimpleQId(SymbolId(SSymbol(rhs))))) =>
        BinaryExpr(Expressions.OpLeq,Expressions.Var(lhs),Expressions.Var(rhs))
    }
    parser.apply(StringSource("(model " + cvc4Res + ")")) match {
      case Failure(exception) => Set()
      case Success(GetModelFunDefResponseSuccess(responses)) => responses.head.funDef.term match {
        case leq: LessThanEqualTerm => Set(extractLeq(leq))
        case AndTerm(term,terms) => terms.map(t => extractLeq(t.asInstanceOf[LessThanEqualTerm])).toSet + extractLeq(term.asInstanceOf[LessThanEqualTerm])
      }
    }
  }

  def hasSecondResult(goal:Goal, assignment: Subst): Boolean = {
    for (a <- assignment) {
      val newSmtTask = toSMTTask(goal,Some(a))
      val newRes = invokeCVC(newSmtTask)
      if (!newRes.isEmpty) return true
    }
    false
  }

  def synthesizeGuard(abductGoal: Goal): Set[Expressions.Expr] = {
    val task = toSMTTask(abductGoal, taskType = DelegatePureSynthesis.TaskType.condition)
    val res = invokeCVC(task)
    if (res.isEmpty) Set.empty
    else  parseConditions(res.get)
  }

  abstract class PureSynthesis(val isFinal: Boolean) extends SynthesisRule with RuleUtils {
    val exceptionQualifier: String = "rule-pure-synthesis"

    def moreOptions(goal: Goal): Seq[RuleResult]

    def apply(goal: Goal): Seq[RuleResult] = {
      if (!goal.env.config.delegatePure || !configured) return Nil
      if (goal.existentials.isEmpty) return Nil

      val smtTask = toSMTTask(goal)
      val cvc4Res = invokeCVC(smtTask)
      if (cvc4Res.isEmpty) Nil
      else {
        //parse me
        val assignments: Subst = parseAssignments(cvc4Res.get)
        val newPost = goal.post.subst(assignments)
        val newCallGoal = goal.callGoal.map(_.updateSubstitution(assignments))
        val newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
        if (isFinal || !DelegatePureSynthesis.hasSecondResult(goal,assignments)) {
          val kont = ExistentialProducer(assignments) >> IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
          val alternatives = RuleResult(List(newGoal), kont, this, goal) :: Nil
          nubBy[RuleResult, Assertion](alternatives, res => res.subgoals.head.post)
        }
        else moreOptions(goal).toList
      }
    }
  }

  object PureSynthesisFinal extends PureSynthesis(true) with InvertibleRule {
    override def toString: String = "PureSynthesisFinal"

    override def moreOptions(goal: Goal): Seq[RuleResult] = Nil
  }

  object PureSynthesisNonfinal extends PureSynthesis(false) {
    override def toString: String = "PureSynthesisNonFinal"

    override def moreOptions(goal: Goal): Seq[RuleResult] = UnificationRules.Pick(goal)
  }

}