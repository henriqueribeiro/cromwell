package wom

import wom.expression.WomExpression

object RuntimeAttributesKeys {
  val DockerKey = "docker"
  val MaxRetriesKey = "maxRetries"

  val CpuKey = "cpu"
  val CpuPlatformKey = "cpuPlatform"
  val CpuMinKey = "cpuMin"
  val CpuMaxKey = "cpuMax"

  val GpuKey = "gpuCount"
  val GpuMinKey = "gpuCountMin"
  val GpuMaxKey = "gpuCountMax"
  val GpuTypeKey = "gpuType"
  
  val DnaNexusInputDirMinKey = "dnaNexusInputDirMin"
  
  val MemoryKey = "memory"
  val MemoryMinKey = "memoryMin"
  val MemoryMaxKey = "memoryMax"
  val sharedMemoryKey = "sharedMemorySize"
  val jobTimeoutKey = "jobTimeout"
  
  val TmpDirMinKey = "tmpDirMin"
  val TmpDirMaxKey = "tmpDirMax"
  val OutDirMinKey = "outDirMin"

  val FailOnStderrKey = "failOnStderr"
  val ContinueOnReturnCodeKey = "continueOnReturnCode"

  // New for WDL 1.1
  // Semantically, this is the same as continueOnReturnCode as the two attributes are combined at the parsing stage
  val ReturnCodesKey = "returnCodes"
}

case class RuntimeAttributes(attributes: Map[String, WomExpression])
