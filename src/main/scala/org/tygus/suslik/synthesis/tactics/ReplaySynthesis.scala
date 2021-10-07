package org.tygus.suslik.synthesis.tactics

import org.tygus.suslik.synthesis.SynConfig
import org.tygus.suslik.synthesis.rules.Rules

class ReplaySynthesis(config: SynConfig) extends PhasedSynthesis(config) {
  var script: List[Int] = config.script

  override def filterExpansions(allExpansions: Seq[(Rules.RuleResult, Double)]): Seq[(Rules.RuleResult, Double)] = {
    // Interactive mode: ask user to pick an expansion
    if (allExpansions.length == 1 || script.isEmpty) allExpansions
    else {
      val choice = script.head
      script = script.tail
      println("script is ")
      script.foreach(println)
      val res = allExpansions(choice) //- 1)
      List(res)
    }
  }
}
