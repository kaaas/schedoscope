package org.schedoscope.dsl.transformations

import com.ottogroup.bi.soda.dsl._
import org.kitesdk.morphline.stdlib.DropRecordBuilder
import org.kitesdk.morphline.api.Command
import org.schedoscope.dsl.Named
import com.otorg.schedoscopeieldLike
import com.ottogroup.bi.soda.dsl.ExternalTransformation

case class MorphlineTransformation(definition: String = "",
  imports: Seq[String] = List(),
  sampling: Int = 100,
  anonymize: Seq[Named] = List(),
  fields: Seq[Named] = List(),
  fieldMapping: Map[FieldLike[_], FieldLike[_]] = Map()) extends ExternalTransformation {
  def name() = "morphline"

  override def versionDigest = Version.digest(resourceHashes :+ definition)

  override def resources() = {
    List()
  }

}