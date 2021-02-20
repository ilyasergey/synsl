package org.tygus.suslik.certification.targets.htt

import org.tygus.suslik.certification.targets.htt.logic.{Hint, Proof}
import org.tygus.suslik.certification.targets.htt.logic.Sentences.CFunSpec
import org.tygus.suslik.certification.targets.htt.program.Program
import org.tygus.suslik.certification.targets.htt.translation.ProofContext.PredicateEnv
import org.tygus.suslik.certification.{Certificate, CertificateOutput, CertificationTarget}

case class HTTCertificate(name: String, preds: PredicateEnv, spec: CFunSpec, auxSpecs: Seq[CFunSpec], proof: Proof, proc: Program, hints: Seq[Hint] = Seq.empty) extends Certificate {
  val target: CertificationTarget = HTT

  // Replace hyphens with underscores
  def sanitize(txt: String): String = txt.replace('-', '_')

  // Import Coq dependencies
  private val prelude =
    """From mathcomp
      |Require Import ssreflect ssrbool ssrnat eqtype seq ssrfun.
      |From fcsl
      |Require Import prelude pred pcm unionmap heap.
      |From HTT
      |Require Import stmod stsep stlog stlogR.
      |From SSL
      |Require Import core.
      |
      |""".stripMargin

  def pp: String = {
    val builder = new StringBuilder
    builder.append(prelude)
    preds.values.foreach(pred => builder.append(pred.pp + "\n"))
    if (hints.nonEmpty) {
      builder.append(hints.map(_.pp).mkString("\n"))
      builder.append("\n\n")
    }

    for (spec <- auxSpecs) {
      builder.append(spec.pp)
      builder.append(s"\n\nVariable ${spec.name} : ${spec.name}_type.\n\n")
    }

    builder.append(spec.pp)
    builder.append("\n\n")

    builder.append(proc.pp)
    builder.append("\n")
    builder.append(proof.pp)
    builder.toString
  }

  override def outputs: List[CertificateOutput] =  List(CertificateOutput(None, sanitize(name), pp))

}