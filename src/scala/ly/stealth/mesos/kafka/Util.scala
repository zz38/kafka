/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import java.util
import scala.collection.JavaConversions._
import scala.util.parsing.json.JSON
import java.io.{File, IOException}
import java.util.Date
import java.text.SimpleDateFormat
import org.apache.mesos.Protos._
import org.apache.mesos.Protos
import java.net.{Inet4Address, InetAddress, NetworkInterface}

object Util {
  Class.forName(kafka.utils.Json.getClass.getName) // init class
  private def parseNumber(s: String): Number =
    if (s.contains(".")) {
      s.toDouble
    } else {
      try { java.lang.Integer.parseInt(s) }
      catch { case e: java.lang.NumberFormatException => s.toLong }
    }

  JSON.globalNumberParser = parseNumber
  JSON.perThreadNumberParser = parseNumber
  private val jsonLock = new Object

  def parseJson(json: String): Map[String, Object] = {
    jsonLock synchronized {
      val node: Map[String, Object] = JSON.parseFull(json).getOrElse(null).asInstanceOf[Map[String, Object]]
      if (node == null) throw new IllegalArgumentException("Failed to parse json: " + json)
      node
    }
  }

  var terminalWidth: Int = getTerminalWidth
  private def getTerminalWidth: Int = {
    val file: File = File.createTempFile("getTerminalWidth", null)
    file.delete()

    var width = 80
    try {
      new ProcessBuilder(List("bash", "-c", "tput cols"))
        .inheritIO().redirectOutput(file).start().waitFor()

      val source = scala.io.Source.fromFile(file)
      width = try Integer.valueOf(source.mkString.trim) finally source.close()
    } catch {
      case e: IOException => /* ignore */
      case e: NumberFormatException => /* ignore */
    }

    file.delete()
    width
  }

  class BindAddress(s: String) {
    private var _source: String = null
    private var _value: String = null
    
    def source: String = _source
    def value: String = _value

    parse
    def parse {
      val idx = s.indexOf(":")
      if (idx != -1) {
        _source = s.substring(0, idx)
        _value = s.substring(idx + 1)
      } else
        _value = s

      if (source != null && source != "if")
        throw new IllegalArgumentException(s)
    }
    
    def resolve(): String = {
      _source match {
        case null => resolveAddress(_value)
        case "if" => resolveInterfaceAddress(_value)
        case _ => throw new IllegalStateException("Failed to resolve " + s)
      }
    }

    def resolveAddress(addressOrMask: String): String = {
      if (!addressOrMask.endsWith("*")) return addressOrMask
      val prefix = addressOrMask.substring(0, addressOrMask.length - 1)
      
      for (ni <- NetworkInterface.getNetworkInterfaces) {
        val address = ni.getInetAddresses.find(_.getHostAddress.startsWith(prefix)).getOrElse(null)
        if (address != null) return address.getHostAddress
      }

      throw new IllegalStateException("Failed to resolve " + s)
    }

    def resolveInterfaceAddress(name: String): String = {
      val ni = NetworkInterface.getNetworkInterfaces.find(_.getName == name).getOrElse(null)
      if (ni == null) throw new IllegalStateException("Failed to resolve " + s)

      val addresses: util.Enumeration[InetAddress] = ni.getInetAddresses
      val address = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(null)
      if (address != null) return address.getHostAddress

      throw new IllegalStateException("Failed to resolve " + s)
    }


    override def hashCode(): Int = 31 * _source.hashCode + _value.hashCode

    override def equals(o: scala.Any): Boolean = {
      if (!o.isInstanceOf[BindAddress]) return false
      val address = o.asInstanceOf[BindAddress]
      _source == address._source && _value == address._value
    }

    override def toString: String = s
  }

  object Str {
    def dateTime(date: Date): String = {
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ssX").format(date)
    }

    def framework(framework: FrameworkInfo): String = {
      var s = ""

      s += id(framework.getId.getValue)
      s += " name: " + framework.getName
      s += " hostname: " + framework.getHostname
      s += " failover_timeout: " + framework.getFailoverTimeout

      s
    }

    def master(master: MasterInfo): String = {
      var s = ""

      s += id(master.getId)
      s += " pid:" + master.getPid
      s += " hostname:" + master.getHostname

      s
    }

    def slave(slave: SlaveInfo): String = {
      var s = ""

      s += id(slave.getId.getValue)
      s += " hostname:" + slave.getHostname
      s += " port:" + slave.getPort
      s += " " + resources(slave.getResourcesList)

      s
    }

    def offer(offer: Offer): String = {
      var s = ""

      s += offer.getHostname + id(offer.getId.getValue)
      s += " " + resources(offer.getResourcesList)
      if (offer.getAttributesCount > 0) s += " " + attributes(offer.getAttributesList)

      s
    }

    def offers(offers: Iterable[Offer]): String = {
      var s = ""

      for (offer <- offers)
        s += (if (s.isEmpty) "" else "\n") + Str.offer(offer)

      s
    }

    def task(task: TaskInfo): String = {
      var s = ""

      s += task.getTaskId.getValue
      s += " slave:" + id(task.getSlaveId.getValue)

      s += " " + resources(task.getResourcesList)
      s += " data:" + new String(task.getData.toByteArray)

      s
    }

    def resources(resources: util.List[Protos.Resource]): String = {
      var s = ""

      val order: util.List[String] = "cpus mem disk ports".split(" ").toList
      for (resource <- resources.sortBy(r => order.indexOf(r.getName))) {
        if (!s.isEmpty) s += "; "

        s += resource.getName
        if (resource.getRole != "*") {
          s += "(" + resource.getRole

          if (resource.hasReservation && resource.getReservation.hasPrincipal)
            s += ", " + resource.getReservation.getPrincipal

          s += ")"
        }

        if (resource.hasDisk)
          s += "[" + resource.getDisk.getPersistence.getId + ":" + resource.getDisk.getVolume.getContainerPath + "]"

        s += ":"

        if (resource.hasScalar)
          s += "%.2f".format(resource.getScalar.getValue)

        if (resource.hasRanges)
          for (range <- resource.getRanges.getRangeList)
            s += "[" + range.getBegin + ".." + range.getEnd + "]"
      }

      s
    }

    def attributes(attributes: util.List[Protos.Attribute]): String = {
      var s = ""

      for (attr <- attributes) {
        if (!s.isEmpty) s += ";"
        s += attr.getName + ":"

        if (attr.hasText) s += attr.getText.getValue
        if (attr.hasScalar) s +=  "%.2f".format(attr.getScalar.getValue)
      }

      s
    }

    def taskStatus(status: TaskStatus): String = {
      var s = ""
      s += status.getTaskId.getValue
      s += " " + status.getState.name()

      s += " slave:" + id(status.getSlaveId.getValue)

      if (status.getState != TaskState.TASK_RUNNING)
        s += " reason:" + status.getReason.name()

      if (status.getMessage != null && status.getMessage != "")
        s += " message:" + status.getMessage

      if (status.getData.size > 0)
        s += " data: " + status.getData.toStringUtf8

      s
    }

    def id(id: String): String = "#" + suffix(id, 5)

    def suffix(s: String, maxLen: Int): String = {
      if (s.length <= maxLen) return s
      s.substring(s.length - maxLen)
    }
  }

  def readLastLines(file: File, n: Int, maxBytes: Int = 102400): String = {
    require(n > 0)

    val raf = new java.io.RandomAccessFile(file, "r")
    val fileLength: Long = raf.length()
    var line = 0
    var pos: Long = fileLength - 1
    var found = false
    var nlPos = pos

    try {
      while (pos != -1 && !found) {
        raf.seek(pos)

        if (raf.readByte() == 10) {
          if (pos != fileLength - 1) {
            line += 1
          }
          nlPos = pos
        }

        if (line == n) found = true
        if (fileLength - pos > maxBytes) found = true

        if (!found) pos -= 1
      }

      if (pos == -1) pos = 0

      if (line == n) {
        pos = pos + 1
      } else if (fileLength - pos > maxBytes) {
        pos = nlPos + 1
      }

      raf.seek(pos)

      val buffer = new Array[Byte]((fileLength - pos).toInt)

      raf.read(buffer, 0, buffer.length)

      val str = new String(buffer, "UTF-8")

      str
    } finally {
      raf.close()
    }
  }
}
