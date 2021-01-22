package org.tygus.suslik.certification

import org.tygus.suslik.certification.targets.vst.translation.ProofTranslation.ProofRuleTranslationException
import org.tygus.suslik.language.Expressions.{Expr, NilPtr, Subst, SubstVar, Var}
import org.tygus.suslik.language.Statements.{Error, Load, Skip, Store}
import org.tygus.suslik.language.{PrettyPrinting, SSLType, Statements}
import org.tygus.suslik.logic.Preprocessor.{findMatchingHeaplets, sameLhs}
import org.tygus.suslik.logic.Specifications.{Assertion, Goal, GoalLabel, SuspendedCallGoal}
import org.tygus.suslik.logic._
import org.tygus.suslik.synthesis.rules.LogicalRules.StarPartial.extendPure
import org.tygus.suslik.synthesis.rules._
import org.tygus.suslik.synthesis._

import scala.collection.immutable.Map



/** compressed form of suslik rules */
sealed abstract class ProofRule extends PrettyPrinting {
  val next1: Seq[ProofRule]
  val cardinality: Int = 1
}

object ProofRule {
  var indent : Int = 0

  def ind : String = " " * indent

  def sanitize (str: String) = str.replace(";\n","")

  def with_scope[A] (f: Unit => A) : A = {
    val old_indent_value = indent
    indent = indent + 4
    val result = f(())
    indent = old_indent_value
    result
  }


  /** corresponds to asserting all the variables in vars are not null */
  case class NilNotLval(vars: List[Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}NilNotLval(${vars.map(_.pp).mkString(", ")});\n${next.pp}"
  }

  /** solves a pure entailment with SMT */
  case class CheckPost(prePhi: PFormula, postPhi: PFormula, next:ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}CheckPost(${prePhi.pp}; ${postPhi.pp});\n${next.pp}"
  }

  /** picks an arbitrary instantiation of the proof rules */
  case class Pick(subst: Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Pick(${subst.mkString(", ")});\n${next.pp}"
  }

  /** abduces a condition for the proof */
  case class AbduceBranch(cond: Expr, bLabel: GoalLabel, ifTrue:ProofRule, ifFalse:ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(ifTrue, ifFalse)
    override val cardinality: Int = 2
    override def pp: String = s"${ind}AbduceBranch(${cond});\n${ind}IfTrue:\n${with_scope(_ => ifTrue.pp)}\n${ind}IfFalse:\n${with_scope(_ => ifFalse.pp)}"
  }

  /** write a value */
  case class Write(stmt: Store, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Write(${sanitize(stmt.pp)});\n${next.pp}"
  }

  /** weaken the precondition by removing unused formulae */
  case class WeakenPre(unused: PFormula, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}WeakenPre(${unused.pp});\n${next.pp}"
  }

  /** empty rule */
  case object EmpRule extends ProofRule {
    val next1: Seq[ProofRule] = Seq.empty
    override val cardinality: Int = 0
    override def pp: String = s"${ind}EmpRule;"
  }

  /** pure synthesis rules */
  case class PureSynthesis(is_final: Boolean, assignments:Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}PureSynthesis(${is_final}, ${assignments.mkString(",")});\n${next.pp}"
  }

  /** open constructor cases */
  case class Open(pred: SApp, fresh_vars: SubstVar, sbst: Subst, cases: List[(Expr, ProofRule)]) extends ProofRule {
    val next1: Seq[ProofRule] = cases.map(_._2)
    override val cardinality: Int = cases.length
    override def pp: String = s"${ind}Open(${pred.pp}, ${fresh_vars.mkString(", ")});\n${with_scope(_ => cases.map({case (expr,rest) => s"${ind}if ${sanitize(expr.pp)}:\n${with_scope(_ => rest.pp)}"}).mkString("\n"))}"
  }

  /** subst L */
  case class SubstL(map: Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}SubstL(${map.mkString(",")});\n${next.pp}"
  }

  /** subst R */
  case class SubstR(map: Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}SubstR(${map.mkString(",")});\n${next.pp}"
  }


  /** read rule */
  case class Read(map: Map[Var,Var], operation: Load, next:ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Read(${map.mkString(",")}, ${sanitize(operation.pp)});\n${next.pp}"
  }

//  /** abduce a call */
case class AbduceCall(
                       new_vars: Map[Var, SSLType],
                       f_pre: Specifications.Assertion,
                       callePost: Specifications.Assertion,
                       call: Statements.Call,
                       freshSub: SubstVar,
                       freshToActual: Subst,
                       f: FunSpec,
                       gamma: Gamma,
                       next: ProofRule
                     ) extends ProofRule {
  val next1: Seq[ProofRule] = Seq(next)
  override def pp: String = s"${ind}AbduceCall({${new_vars.mkString(",")}}, ${sanitize(f_pre.pp)}, ${sanitize(callePost.pp)}, ${sanitize(call.pp)}, {${freshSub.mkString(",")}});\n${next.pp}"
}


  /** unification of heap (ignores block/pure distinction) */
  case class HeapUnify(subst: Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}HeapUnify(${subst.mkString(",")});\n${next.pp}"
  }

  /** unification of pointers */
  case class HeapUnifyPointer(map: Map[Var,Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}HeapUnifyPointer(${map.mkString(",")});\n${next.pp}"
  }

  /** unfolds frame */
  case class FrameUnfold(h_pre: Heaplet, h_post: Heaplet, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}FrameUnfold(${h_pre.pp}, ${h_post.pp});\n${next.pp}"
  }

  /** call operation */
  case class Call(subst: Map[Var, Expr], call: Statements.Call, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Call({${subst.mkString(",")}}, ${sanitize(call.pp)});\n${next.pp}"
  }

  /** free operation */
  case class Free(stmt: Statements.Free, size: Int, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Free(${sanitize(stmt.pp)});\n${next.pp}"
  }

  /** malloc rule */
  case class Malloc(map: SubstVar, stmt: Statements.Malloc, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Malloc(${map.mkString(",")}, ${sanitize(stmt.pp)});\n${next.pp}"
  }

  /** close rule */
  case class Close(app: SApp, selector: Expr, asn: Assertion, fresh_exist: SubstVar, next: ProofRule) extends  ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Close(${app.pp}, ${sanitize(selector.pp)}, ${asn.pp}, {${fresh_exist.mkString(",")}});\n${next.pp}"
  }

  /** star partial */
  case class StarPartial(new_pre_phi: PFormula, new_post_phi: PFormula, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}StarPartial(${new_pre_phi.pp}, ${new_post_phi.pp});\n${next.pp}"
  }

  case class PickCard(map: Map[Var,Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}PickCard(${map.mkString(",")});\n${next.pp}"
  }


  case class PickArg(map: Map[Var, Expr], next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}PickArg(${map.mkString(",")});\n${next.pp}"
  }

  case class Init(goal: Goal, next: ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(next)
    override def pp: String = s"${ind}Init(${goal.pp});\n${next.pp}"
  }

  case object Inconsistency extends ProofRule {
    val next1: Seq[ProofRule] = Seq.empty
    override val cardinality: Int = 0
    override def pp: String = "Inconsistency"
  }

  case class Branch(cond: Expr, ifTrue:ProofRule, ifFalse:ProofRule) extends ProofRule {
    val next1: Seq[ProofRule] = Seq(ifTrue, ifFalse)
    override val cardinality: Int = 2
    override def pp: String = s"${ind}Branch($cond);\n${ind}IfTrue:\n${with_scope(_ => ifTrue.pp)}\n${ind}IfFalse:\n${with_scope(_ => ifFalse.pp)}"
  }

  /** converts a Suslik CertTree node into the unified ProofRule structure */
  def of_certtree(node: CertTree.Node): ProofRule = {
    def fail_with_bad_proof_structure(): Nothing =
      throw ProofRuleTranslationException(s"continuation for ${node.rule} is not what was expected: ${node.kont.toString}")
    def fail_with_bad_children(ls: Seq[CertTree.Node], count: Int): Nothing =
      throw ProofRuleTranslationException(s"unexpected number of children for proof rule ${node.rule} - ${ls.length} != $count")

    def visit(node: CertTree.Node): (ProofRule, Map[ProofRule, GoalLabel]) = {
      val (r, labels: Map[ProofRule, GoalLabel]) = node.rule match {
        case LogicalRules.NilNotLval => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(_)) =>
            // find all pointers that are not yet known to be non-null
            def find_pointers(p: PFormula, s: SFormula): Set[Expr] = {
              // All pointers
              val allPointers = (for (PointsTo(l, _, _) <- s.chunks) yield l).toSet
              allPointers.filter(
                x => !p.conjuncts.contains(x |/=| NilPtr) && !p.conjuncts.contains(NilPtr |/=| x)
              )
            }

            val pre_pointers = find_pointers(node.goal.pre.phi, node.goal.pre.sigma).toList

            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.NilNotLval(pre_pointers, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case FailRules.CheckPost => node.kont match {
          case ChainedProducer(PureEntailmentProducer(prePhi, postPhi), IdProducer) => node.children match {
            case ::(head, Nil) =>
              val (next, labels) = visit(head)
              (ProofRule.CheckPost(prePhi, postPhi, next), labels)
            case ls => fail_with_bad_children(ls, 1)
          }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.Pick => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(map), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Pick(map, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case FailRules.AbduceBranch => node.kont match {
          case GuardedProducer(cond, bgoal) =>
            node.children match {
              case ::(if_true, ::(if_false, Nil)) =>
                val (if_true1, labels) = visit(if_true)
                val (if_false1, labels1) = visit(if_false)
                (ProofRule.AbduceBranch(cond, bgoal.label, if_true1, if_false1), labels ++ labels1)
              case ls => fail_with_bad_children(ls, 2)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case OperationalRules.WriteRule => node.kont match {
          case ChainedProducer(ChainedProducer(PrependProducer(stmt@Store(_, _, _)), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Write(stmt, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.Inconsistency => node.kont match {
          case ConstProducer(Error) =>
            node.children match {
              case Nil => (ProofRule.Inconsistency, Map.empty)
              case ls => fail_with_bad_children(ls, 0)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.WeakenPre => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            val unused = goal.pre.phi.indepedentOf(goal.pre.sigma.vars ++ goal.post.vars)
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.WeakenPre(unused, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.EmpRule => node.kont match {
          case ConstProducer(Skip) =>
            node.children match {
              case Nil => (ProofRule.EmpRule, Map.empty)
              case ls => fail_with_bad_children(ls, 0)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case DelegatePureSynthesis.PureSynthesisFinal => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(assignments), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.PureSynthesis(is_final = true, assignments, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnfoldingRules.Open => node.kont match {
          case ChainedProducer(ChainedProducer(BranchProducer(Some(pred), fresh_vars, sbst, selectors), HandleGuard(_)), ExtractHelper(_)) =>
            val (next, labels) = node.children.map(visit).unzip
            (ProofRule.Open(pred, fresh_vars, sbst, selectors.zip(next).toList), labels.reduceOption[Map[ProofRule, GoalLabel]](_ ++ _).getOrElse(Map.empty))
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.SubstLeft => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(map), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.SubstL(map, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.SubstRight => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(map), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.SubstR(map, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case OperationalRules.ReadRule => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(GhostSubstProducer(map), PrependProducer(stmt@Load(_, _, _, _))), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Read(map, stmt, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnfoldingRules.AbduceCall => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(AbduceCallProducer(f), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                // find out which new variables were added to the context
                val new_vars =
                  head.goal.gamma.filterKeys(key => !node.goal.gamma.contains(key))
                val f_pre = head.goal.post
                var SuspendedCallGoal(caller_pre, caller_post, callePost, call, freshSub, freshToActual) = head.goal.callGoal.get
                val (next, labels) = visit(head)
                (ProofRule.AbduceCall(new_vars, f_pre, callePost, call, freshSub, freshToActual, f, head.goal.gamma, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.HeapUnifyPure => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(subst), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.HeapUnify(subst, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.HeapUnifyUnfolding => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(subst), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.HeapUnify(subst, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.HeapUnifyBlock => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(subst), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.HeapUnify(subst, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnificationRules.HeapUnifyPointer => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(subst), IdProducer), HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.HeapUnifyPointer(subst, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.FrameUnfolding => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val pre = goal.pre
                val post = goal.post

                def isMatch(hPre: Heaplet, hPost: Heaplet): Boolean = hPre.eqModTags(hPost) && LogicalRules.FrameUnfolding.heapletFilter(hPost)

                findMatchingHeaplets(_ => true, isMatch, pre.sigma, post.sigma) match {
                  case None => ???
                  case Some((h_pre, h_post)) =>
                    val (next, labels) = visit(head)
                    (ProofRule.FrameUnfold(h_pre, h_post, next), labels)
                }
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.FrameUnfoldingFinal => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val pre = goal.pre
                val post = goal.post

                def isMatch(hPre: Heaplet, hPost: Heaplet): Boolean = hPre.eqModTags(hPost) && LogicalRules.FrameUnfoldingFinal.heapletFilter(hPost)

                findMatchingHeaplets(_ => true, isMatch, pre.sigma, post.sigma) match {
                  case None => ???
                  case Some((h_pre, h_post)) =>
                    val (next, labels) = visit(head)
                    (ProofRule.FrameUnfold(h_pre, h_post, next), labels)
                }
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.FrameBlock => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val pre = goal.pre
                val post = goal.post

                def isMatch(hPre: Heaplet, hPost: Heaplet): Boolean = hPre.eqModTags(hPost) && LogicalRules.FrameBlock.heapletFilter(hPost)

                findMatchingHeaplets(_ => true, isMatch, pre.sigma, post.sigma) match {
                  case None => ???
                  case Some((h_pre, h_post)) =>
                    val (next, labels) = visit(head)
                    (ProofRule.FrameUnfold(h_pre, h_post, next), labels)
                }
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case LogicalRules.FrameFlat => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val pre = goal.pre
                val post = goal.post

                def isMatch(hPre: Heaplet, hPost: Heaplet): Boolean = hPre.eqModTags(hPost) && LogicalRules.FrameFlat.heapletFilter(hPost)

                findMatchingHeaplets(_ => true, isMatch, pre.sigma, post.sigma) match {
                  case None => ???
                  case Some((h_pre, h_post)) =>
                    val (next, labels) = visit(head)
                    (ProofRule.FrameUnfold(h_pre, h_post, next), labels)
                }
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnfoldingRules.CallRule => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(subst), PrependProducer(call: Statements.Call)), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Call(subst, call, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case OperationalRules.FreeRule => node.kont match {
          case ChainedProducer(ChainedProducer(PrependProducer(stmt@Statements.Free(Var(name))), HandleGuard(_)), ExtractHelper(_)) =>
            val size: Int = node.goal.pre.sigma.blocks.find({ case Block(Var(ploc), sz) => ploc == name }).map({ case Block(_, sz) => sz }) match {
              case Some(value) => value
              case None => 1
            }
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Free(stmt, size, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case OperationalRules.AllocRule => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(GhostSubstProducer(map), PrependProducer(stmt@Statements.Malloc(_, _, _))), HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Malloc(map, stmt, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }
        case UnfoldingRules.Close => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(UnfoldProducer(app, selector, asn, fresh_exist), IdProducer), HandleGuard(_)), ExtractHelper(_)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.Close(app, selector, asn, fresh_exist, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
        }
        case LogicalRules.StarPartial => node.kont match {
          case ChainedProducer(ChainedProducer(IdProducer, HandleGuard(_)), ExtractHelper(goal)) =>
            val new_pre_phi = extendPure(goal.pre.phi, goal.pre.sigma)
            val new_post_phi = extendPure(goal.pre.phi && goal.post.phi, goal.post.sigma)

            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.StarPartial(new_pre_phi, new_post_phi, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
          case _ => fail_with_bad_proof_structure()
        }

        case UnificationRules.PickCard => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(map), IdProducer), HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.PickCard(map, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
        }
        case UnificationRules.PickArg => node.kont match {
          case ChainedProducer(ChainedProducer(ChainedProducer(SubstProducer(map), IdProducer), HandleGuard(_)), ExtractHelper(goal)) =>
            node.children match {
              case ::(head, Nil) =>
                val (next, labels) = visit(head)
                (ProofRule.PickArg(map, next), labels)
              case ls => fail_with_bad_children(ls, 1)
            }
        }
      }
      (r, labels + (r -> node.goal.label))
    }
    val (root, labels) = visit(node)
    finalize_branches(root, labels)
  }

  /**
    *
    * Finalizes the branches abduced by applications of the AbduceBranch rule.
    *
    * Consider in the left diagram, an AbduceBranch node B with children C (true case) and D (false case), and
    * intended branch destination A. This procedure inserts a new Branch node E with A as its true case child; the
    * inserted node E is meant to denote a finalized branching point.
    *
    *           D---            D---    D---
    *          /       =>      /       /
    * --A-----B-C---        --E-A-----B-C---
    *
    * @param node the root node of the tree
    * @param labels the goal label associated with each proof rule, used to look up the branch destination
    * @return a copy of the tree with finalized branches inserted
    */
  def finalize_branches(node: ProofRule, labels: Map[ProofRule, GoalLabel]): ProofRule = {
    def collect_branch_abductions(node: ProofRule, acc: Set[AbduceBranch] = Set.empty): Set[AbduceBranch] = node match {
      case ab:AbduceBranch => node.next1.foldLeft(acc + ab){ case (acc, next) => collect_branch_abductions(next, acc) }
      case _ => node.next1.foldLeft(acc){ case (acc, next) => collect_branch_abductions(next, acc) }
    }
    def apply_branch_abductions(node: ProofRule, known_abductions: Set[AbduceBranch]) : ProofRule = {
      def with_next(node: ProofRule, next: Seq[ProofRule]): ProofRule = node match {
        case r:ProofRule.NilNotLval=> r.copy(next = next.head)
        case r:ProofRule.CheckPost => r.copy(next = next.head)
        case r:ProofRule.Pick => r.copy(next = next.head)
        case r:ProofRule.AbduceBranch =>
          val Seq(ifTrue, ifFalse) = next
          r.copy(ifTrue = ifTrue, ifFalse = ifFalse)
        case r:ProofRule.Branch =>
          val Seq(ifTrue, ifFalse) = next
          r.copy(ifTrue = ifTrue, ifFalse = ifFalse)
        case r:ProofRule.Write => r.copy(next = next.head)
        case r:ProofRule.WeakenPre => r.copy(next = next.head)
        case r:ProofRule.PureSynthesis => r.copy(next = next.head)
        case r:ProofRule.Open => r.copy(cases = r.cases.zip(next).map(c => (c._1._1, c._2)))
        case r:ProofRule.SubstL => r.copy(next = next.head)
        case r:ProofRule.SubstR => r.copy(next = next.head)
        case r:ProofRule.Read => r.copy(next = next.head)
        case r:ProofRule.AbduceCall => r.copy(next = next.head)
        case r:ProofRule.HeapUnify => r.copy(next = next.head)
        case r:ProofRule.HeapUnifyPointer => r.copy(next = next.head)
        case r:ProofRule.FrameUnfold => r.copy(next = next.head)
        case r:ProofRule.Call => r.copy(next = next.head)
        case r:ProofRule.Free => r.copy(next = next.head)
        case r:ProofRule.Malloc => r.copy(next = next.head)
        case r:ProofRule.Close => r.copy(next = next.head)
        case r:ProofRule.StarPartial => r.copy(next = next.head)
        case r:ProofRule.PickCard => r.copy(next = next.head)
        case r:ProofRule.PickArg => r.copy(next = next.head)
        case r:ProofRule.Init => r.copy(next = next.head)
        case ProofRule.EmpRule => ProofRule.EmpRule
        case ProofRule.Inconsistency => ProofRule.Inconsistency
      }
      val nodeLabel = labels(node)
      known_abductions.find(_.bLabel == nodeLabel) match {
        case Some(AbduceBranch(cond, bLabel, ifTrue, ifFalse)) =>
          assert(node.cardinality == 1)
          val ifTrue1 = with_next(node, Seq(apply_branch_abductions(node.next1.head, known_abductions)))
          val ifFalse1 = apply_branch_abductions(ifFalse, known_abductions)
          Branch(cond, ifTrue1, ifFalse1)
        case None =>
          val next = node.next1.map(next => apply_branch_abductions(next, known_abductions))
          with_next(node, next)
      }
    }
    val abductions = collect_branch_abductions(node)
    apply_branch_abductions(node, abductions)
  }
}