package org.tygus.synsl.synthesis.rules

import org.tygus.synsl.LanguageUtils
import org.tygus.synsl.LanguageUtils.generateFreshVar
import org.tygus.synsl.language.Expressions._
import org.tygus.synsl.language.{Statements, _}
import org.tygus.synsl.logic._
import org.tygus.synsl.synthesis._
import org.tygus.synsl.synthesis.rules.SubtractionRules.makeRuleApp

/**
  * @author Nadia Polikarpova, Ilya Sergey
  */

object OperationalRules extends SepLogicUtils with RuleUtils {

  val exceptionQualifier: String = "rule-operational"

  import Statements._

  // TODO: Implement [cond]
  // TODO: Implement [call]

  /*
  Write rule: create a new write from where it's possible

  Γ ; {φ ; x.f -> l' * P} ; {ψ ; x.f -> l' * Q} ---> S   GV(l) = GV(l') = Ø
  ------------------------------------------------------------------------- [write]
  Γ ; {φ ; x.f -> l * P} ; {ψ ; x.f -> l' * Q} ---> *x.f := l' ; S

  */
  object WriteRuleOld extends SynthesisRule {

    override def toString: Ident = "[Op: write-old]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      val pre = goal.pre
      val post = goal.post

      // Heaplets have no ghosts
      def noGhosts: Heaplet => Boolean = {
        case PointsTo(x@(Var(_)), _, e) => !goal.isGhost(x) && e.vars.forall(v => !goal.isGhost(v))
        case _ => false
      }

      // When do two heaplets match
      def isMatch(hl: Heaplet, hr: Heaplet) = sameLhs(hl)(hr) && noGhosts(hr)

      findMatchingHeaplets(noGhosts, isMatch, goal.pre.sigma, goal.post.sigma) match {
        case None => Nil
        case Some((hl@(PointsTo(x@Var(_), offset, e1)), hr@(PointsTo(_, _, e2)))) =>
          if (e1 == e2) {
            return Nil
          } // Do not write if RHSs are the same

          val newPre = Assertion(pre.phi, goal.pre.sigma - hl)
          val newPost = Assertion(post.phi, goal.post.sigma - hr)
          val subGoal = goal.copy(newPre, newPost)
          val kont: StmtProducer = stmts => {
            ruleAssert(stmts.lengthCompare(1) == 0, s"Write rule expected 1 premise and got ${stmts.length}")
            val rest = stmts.head
            SeqComp(Store(x, offset, e2), rest)
          }

          List(Subderivation(List((subGoal, env)), kont))
        case Some((hl, hr)) =>
          ruleAssert(assertion = false, s"Write rule matched unexpected heaplets ${hl.pp} and ${hr.pp}")
          Nil
      }
    }
  }


  /*
    Γ, l ; {φ ; x.f -> l * P} ; {ψ ; x.f -> l * Q}[l/m] ---> S   m is existential
  --------------------------------------------------------------------------------[pick-from-env]
     Γ ; {φ ; x.f -> - * P} ; {ψ ; x.f -> m * Q} ---> *x.f := l ; S
   */
  object PickFromEnvRule extends SynthesisRule with InvertibleRule {

    override def toString: Ident = "[Op: write-from-env]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      val pre = goal.pre
      val post = goal.post

      def isSuitable: Heaplet => Boolean = {
        case PointsTo(x@(Var(_)), _, v@Var(_)) =>
          !goal.isGhost(x) && goal.isExistential(v) && LanguageUtils.isNotDefaultFreshVar(v)
        case _ => false
      }

      def noGhosts: Heaplet => Boolean = {
        case PointsTo(x@(Var(_)), _, e) => !goal.isGhost(x) && e.vars.forall(v => !goal.isGhost(v))
        case _ => false
      }

      // When do two heaplets match
      def isMatch(hl: Heaplet, hr: Heaplet) = sameLhs(hl)(hr) && isSuitable(hr)

      if (post.sigma.chunks.size > 1) return Nil

      findMatchingHeaplets(noGhosts, isMatch, goal.pre.sigma, goal.post.sigma) match {
        case None => Nil
        case Some((hl@(PointsTo(x@Var(_), offset, _)), hr@(PointsTo(_, _, m@Var(_))))) =>
          for {
          // Try variables from the context
            (_, l) <- goal.gamma.toList
            newPre = Assertion(pre.phi, (goal.pre.sigma - hl) ** PointsTo(x, offset, l))
            subGoal = goal.copy(newPre, post.subst(m, l))
            kont = (stmts: Seq[Statement]) => {
              ruleAssert(stmts.lengthCompare(1) == 0, s"Write rule expected 1 premise and got ${stmts.length}")
              val rest = stmts.head
              SeqComp(Store(x, offset, l), rest)
            }
          } yield Subderivation(List((subGoal, env)), kont)
        case Some((hl, hr)) =>
          ruleAssert(false, s"Write rule matched unexpected heaplets ${hl.pp} and ${hr.pp}")
          Nil
      }
    }
  }



  /*
  Write rule: create a new write from where it's possible

  Γ ; {φ ; P} ; {ψ ; x.f -> Y * Q} ---> S   GV(l) = Ø  Y is fresh
  ------------------------------------------------------------------------- [write]
  Γ ; {φ ; P} ; {ψ ; x.f -> l * Q} ---> S; *x.f := l

  */
  object WriteRule extends SynthesisRule with InvertibleRule {

    override def toString: Ident = "[Op: write]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      val post = goal.post

      // Heaplets have no ghosts
      def noGhosts: Heaplet => Boolean = {
        case PointsTo(x@(Var(_)), _, e) => !goal.isGhost(x) && e.vars.forall(v => !goal.isGhost(v))
        case _ => false
      }

      findHeaplet(noGhosts, post.sigma) match {
        case None => Nil
        case Some(h@(PointsTo(x@Var(_), offset, l))) =>
          val y = generateFreshVar(goal)

          val newPost = Assertion(post.phi, (post.sigma - h) ** PointsTo(x, offset, y))
          val subGoal = goal.copy(post = newPost)
          val kont: StmtProducer = stmts => {
            ruleAssert(stmts.lengthCompare(1) == 0, s"Write rule expected 1 premise and got ${stmts.length}")
            val rest = stmts.head
            SeqComp(rest, Store(x, offset, l))
          }
          List(Subderivation(List((subGoal, env)), kont))
        case Some(h) =>
          ruleAssert(false, s"Write rule matched unexpected heaplet ${h.pp}")
          Nil
      }
    }
  }
  /*
  Read rule: create a fresh typed read

        y is fresh   Γ,y ; [y/A]{φ ; x -> A * P} ; [y/A]{ψ ; Q} ---> S
      ---------------------------------------------------------------- [read]
             Γ ; {φ ; x.f -> A * P} ; {ψ ; Q} ---> let y := *x.f ; S
  */
  object ReadRule extends SynthesisRule with InvertibleRule {

    override def toString: Ident = "[Op: read]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      val pre = goal.pre
      val post = goal.post
      val gamma = goal.gamma

      def isGhostPoints: Heaplet => Boolean = {
        case PointsTo(x@Var(_), _, a@(Var(_))) =>
          goal.isGhost(a) && !goal.isGhost(x)
        case _ => false
      }

      findHeaplet(isGhostPoints, goal.pre.sigma) match {
        case None => Nil
        case Some(PointsTo(x@Var(_), offset, a@(Var(_)))) =>
          val y = generateFreshVar(goal, a.name)
          val tpy = goal.getType(a)

          val subGoal = goal.copy(pre.subst(a, y),
            post = post.subst(a, y),
            gamma = (tpy, y) :: gamma.toList)
          val kont: StmtProducer = stmts => {
            ruleAssert(stmts.lengthCompare(1) == 0, s"Read rule expected 1 premise and got ${stmts.length}")
            val rest = stmts.head
            // Do not generate read for unused variables
            if (rest.usedVars.contains(y)) SeqComp(Load(y, tpy, x, offset), rest) else rest
          }

          List(Subderivation(List((subGoal, env)), kont))
        case Some(h) =>
          ruleAssert(false, s"Read rule matched unexpected heaplet ${h.pp}")
          Nil
      }
    }
  }

  /*
  Alloc rule: allocate memory for an existential block

           X ∈ GV(post) / GV (pre)        y, Ai fresh
         Γ ; {φ ; y -> (A0 .. An) * P} ; {ψ ; [y/X]Q} ---> S
     -------------------------------------------------------------- [alloc]
     Γ ; {φ ; P} ; {ψ ; block(X, n) * Q} ---> let y = malloc(n); S
  */
  object AllocRule extends SynthesisRule {
    override def toString: Ident = "[Op: alloc]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      val pre = goal.pre
      val post = goal.post
      val gamma = goal.gamma

      def isExistBlock(goal: Goal): Heaplet => Boolean = {
        case Block(x@Var(_), _) => goal.isExistential(x)
        case _ => false
      }

      findHeaplet(isExistBlock(goal), post.sigma) match {
        case None => Nil
        case Some(h@(Block(x@Var(_), sz))) =>
          val newPost = Assertion(post.phi, post.sigma)
          val y = generateFreshVar(goal, x.name)
          val tpy = LocType

          val freshChunks = for {
            off <- 0 until sz
            z = generateFreshVar(goal)
          } yield PointsTo(y, off, z)
          // yield PointsTo(y, off, IntConst(0))
          val freshBlock = Block(x, sz).subst(x, y)
          val newPre = Assertion(pre.phi, SFormula(pre.sigma.chunks ++ freshChunks ++ List(freshBlock)))
          val subGoal = goal.copy(newPre, newPost.subst(x, y), (tpy, y) :: gamma.toList)
          val kont: StmtProducer = stmts => {
            ruleAssert(stmts.lengthCompare(1) == 0, s"Alloc rule expected 1 premise and got ${stmts.length}")
            SeqComp(Malloc(y, tpy, sz), stmts.head)
          }

          List(Subderivation(List((subGoal, env)), kont))
        case Some(h) =>
          ruleAssert(false, s"Alloc rule matched unexpected heaplet ${h.pp}")
          Nil
      }
    }

  }

  /*
  Free rule: free a non-ghost block from the pre-state

                     Γ ; {φ ; P} ; {ψ ; Q} ---> S     GV(li) = Ø
   ------------------------------------------------------------------------ [free]
   Γ ; {φ ; block(x, n) * x -> (l1 .. ln) * P} ; { ψ ; Q } ---> free(x); S
*/
  object FreeRule extends SynthesisRule {

    override def toString: Ident = "[Op: free]"

    def apply(goal: Goal, env: Environment): Seq[Subderivation] = {
      def isConcreteBlock: Heaplet => Boolean = {
        case Block(v@(Var(_)), _) => goal.isConcrete(v)
        case _ => false
      }

      val pre = goal.pre
      val deriv = goal.deriv

      findHeaplet(isConcreteBlock, goal.pre.sigma) match {
        case None => Nil
        case Some(h@Block(x@(Var(_)), sz)) =>
          // Okay, found the block, now looking for the points-to chunks
          val pts = for (off <- 0 until sz) yield {
            findHeaplet(sameLhs(PointsTo(x, off, IntConst(0))), goal.pre.sigma) match {
              case Some(pt) if pt.vars.forall(!goal.isGhost(_)) => pt
              case _ => return Nil
            }
          }
          val newPre = Assertion(pre.phi, pre.sigma - h - pts)

          // Collecting the footprint
          val preFootprint = pts.map(p => deriv.preIndex.indexOf(p)).toSet + deriv.preIndex.indexOf(h)
          val ruleApp = makeRuleApp(this.toString, (preFootprint, Set.empty), deriv)

          val subGoal = goal.copy(newPre, newRuleApp = Some(ruleApp))
          val kont: StmtProducer = stmts => {
            ruleAssert(stmts.lengthCompare(1) == 0, s"Free rule expected 1 premise and got ${stmts.length}")
            SeqComp(Free(x), stmts.head)
          }

          List(Subderivation(List((subGoal, env)), kont))
        case Some(h) =>
          ruleAssert(false, s"Free rule matched unexpected heaplet ${h.pp}")
          Nil
      }
    }

  }

}