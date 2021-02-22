package org.tygus.suslik.certification.targets.iris.heaplang

import org.tygus.suslik.language.PrettyPrinting

object Types {

  sealed abstract class HType extends PrettyPrinting

  case class HIntType() extends HType {
    override def pp: String = "Z"
  }
  case class HLocType() extends HType {
    override def pp: String = "loc"
  }
  case class HCardType(predType: String) extends HType {
    override def pp: String = s"${predType}_card"
  }
  case class HUnknownType() extends HType {
    override def pp: String = "_"
  }

  case class HIntSetType() extends HType {
    override def pp: String = "(list Z)"
  }
}
