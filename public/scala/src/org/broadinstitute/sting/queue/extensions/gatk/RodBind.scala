package org.broadinstitute.sting.queue.extensions.gatk

import java.io.File
import org.broadinstitute.sting.utils.io.FileExtension
import java.lang.String

/**
 * Used to provide -B rodBind arguments to the GATK.
 */
class RodBind(var trackName: String, var trackType: String, path: String, val tag: String) extends File(path) with FileExtension {
  def this(trackName: String, trackType: String, path: String) =
    this(trackName, trackType, path, null)
  def this(trackName: String, trackType: String, file: File, tag: String) =
    this(trackName, trackType, file.getPath, tag)
  def this(trackName: String, trackType: String, file: File) =
    this(trackName, trackType, file.getPath, null)
  require(trackName != null, "RodBind trackName cannot be null")
  require(trackType != null, "RodBind trackType cannot be null")
  def withPath(newPath: String) = new RodBind(trackName, trackType, newPath, tag)
}

/**
 * Used to provide -B rodBind arguments to the GATK.
 */
object RodBind {
  def apply(trackName: String, trackType: String, path: String, tag: String) = new RodBind(trackName, trackType, path, tag)
  def apply(trackName: String, trackType: String, path: String) = new RodBind(trackName, trackType, path, null)
  def apply(trackName: String, trackType: String, file: File, tag: String) = new RodBind(trackName, trackType, file, tag)
  def apply(trackName: String, trackType: String, file: File) = new RodBind(trackName, trackType, file, null)

  def formatCommandLineParameter( cmdLineParam: String, value: Any ) = {
    value match {
      case rodBind: RodBind if (rodBind.tag != null) =>
        "%s:%s,%s,%s".format(cmdLineParam, rodBind.trackName, rodBind.trackType, rodBind.tag)
      case rodBind: RodBind =>
        "%s:%s,%s".format(cmdLineParam, rodBind.trackName, rodBind.trackType)
      case x =>
        ""
    }
  }
}
