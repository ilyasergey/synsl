package org.tygus.suslik.logic

import org.tygus.suslik.language.Expressions._
import org.tygus.suslik.language.{BoolType, HasExpressions, PrettyPrinting, SSLType}

import scala.collection.immutable.SortedSet


case class PFormula(conjuncts: SortedSet[Expr]) extends PrettyPrinting with HasExpressions[PFormula] {
  def toExpr: Expr = if (conjuncts.isEmpty) eTrue else conjuncts.reduce(_ && _)

  override def pp: String = toExpr.pp

  def subst(sigma: Subst): PFormula = PFormula(conjuncts.map(_.subst(sigma)))

  def resolveOverloading(gamma: Gamma): PFormula = PFormula(conjuncts.map(_.resolveOverloading(gamma)))

  def resolve(gamma: Gamma): Option[Gamma] = {
    conjuncts.foldLeft[Option[Map[Var, SSLType]]](Some(gamma))((go, c) => go match {
      case None => None
      case Some(g) => c.resolve(g, Some(BoolType))
    })
  }

  // Collect certain sub-expressions
  def collect[R <: Expr](p: Expr => Boolean): Set[R] = {
    conjuncts.map(_.collect(p)).foldLeft(Set.empty[R])(_ ++ _)
  }

  // Add h to chunks (multiset semantics)
  def &&(c: Expr): PFormula = PFormula(conjuncts ++ c.conjuncts.toSet)

  // Add all chunks from other (multiset semantics)
  def &&(other: PFormula): PFormula = PFormula(conjuncts ++ other.conjuncts)

  // Remove h from this formula (multiset semantics)
  def -(c: Expr): PFormula = PFormula(conjuncts -- c.conjuncts.toSet)

  // Remove all chunks present in other (multiset semantics)
  def -(other: PFormula): PFormula = PFormula(conjuncts -- other.conjuncts)

  def ==> (other: PFormula): Expr = this.toExpr ==> other.toExpr

  def ==> (other: Expr): Expr = this.toExpr ==> other

  def size: Int = conjuncts.map(_.size).sum
}

object PFormula {
  def apply(cs: Set[Expr]): PFormula = PFormula(SortedSet[Expr]() ++ cs)

  def apply(c: Expr): PFormula = PFormula(SortedSet[Expr]() ++ c.conjuncts)
}