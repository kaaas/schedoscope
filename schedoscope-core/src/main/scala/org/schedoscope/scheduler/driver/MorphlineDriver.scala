/**
 * Copyright 2015 Otto (GmbH & Co KG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.scheduler.driver

import java.net.URI
import java.security.PrivilegedAction

import com.typesafe.config.{ ConfigFactory, ConfigValueFactory }
import morphlineutils.morphline.{ CommandConnector, MorphlineClasspathUtil }
import morphlineutils.morphline.command.{ CSVWriterBuilder, ExasolWriterBuilder, JDBCWriterBuilder, REDISWriterBuilder }
import morphlineutils.morphline.command.anonymization.{ AnonymizeAvroBuilder, AnonymizeBuilder }
import morphlineutils.morphline.command.sink.AvroWriterBuilder
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ FileSystem, Path }
import org.apache.hadoop.security.UserGroupInformation
import org.joda.time.LocalDateTime
import org.kitesdk.morphline.api.{ Command, MorphlineContext, Record }
import org.kitesdk.morphline.avro.{ ExtractAvroTreeBuilder, ReadAvroContainerBuilder }
import org.kitesdk.morphline.base.{ Fields, Notifications }
import org.kitesdk.morphline.hadoop.parquet.avro.ReadAvroParquetFileBuilder
import org.kitesdk.morphline.stdio.ReadCSVBuilder
import org.kitesdk.morphline.stdlib.{ DropRecordBuilder, PipeBuilder, SampleBuilder }
import org.schedoscope.{ DriverSettings, Schedoscope }
import org.schedoscope.dsl.storageformats._
import org.schedoscope.dsl.transformations.MorphlineTransformation
import org.schedoscope.scheduler.driver.Helper._
import org.slf4j.LoggerFactory

import scala.Array.canBuildFrom
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.Future
import scala.language.implicitConversions

/**
 * Small helper object that implicitly wraps stuff into TypesafeConfig Value instances.
 * needed a lot for configuring morphlines
 *
 */
object Helper {
  implicit def AnyToConfigValue(x: Any) = ConfigValueFactory.fromAnyRef(x, "")
}

/**
 * Driver that executes morphlines. see http://kitesdk.org. The JSON-Specification will be
 * parsed but the overall morphline is assembled programmatically. The first and last element
 * of the Morphline is provided by schedoscope depending on the storage format of the views.
 *
 */
class MorphlineDriver(val driverRunCompletionHandlerClassNames: List[String], val ugi: UserGroupInformation, val hadoopConf: Configuration) extends Driver[MorphlineTransformation] {
  val context = new MorphlineContext.Builder().build()

  def transformationName: String = "morphline"

  val log = LoggerFactory.getLogger(classOf[MorphlineDriver])

  override def run(t: MorphlineTransformation): DriverRunHandle[MorphlineTransformation] = {
    val f = Future {
      try {
        runMorphline(createMorphline(t), t)
      } catch {
        case e: Throwable => log.error("Error when creating morphline", e); DriverRunFailed[MorphlineTransformation](this, "could not create morphline ", e)
      }
    }

    new DriverRunHandle[MorphlineTransformation](this, new LocalDateTime(), t, f)
  }

  /**
   * Creates a morphline command according to a schedoscope storage format
   *
   * @param format
   * @param sourceTable
   * @param cols
   * @param finalCommand
   * @return
   */
  private def createInput(format: StorageFormat, sourceTable: String, cols: Seq[String], finalCommand: Command): Command = {
    // stub to overcome checkNull()s when creating morphlines
    val root = new Command {
      override def getParent(): Command = null

      override def notify(notification: Record) {
        throw new UnsupportedOperationException("Root command should be invisible and must not be called")
      }

      override def process(record: Record): Boolean = {
        throw new UnsupportedOperationException("Root command should be invisible and must not be called")
      }
    }

    format match {
      case f: TextFile => {
        new ReadCSVBuilder().build(ConfigFactory.empty().
          withValue("separator", f.fieldTerminator).
          withValue("trim", true).
          withValue("ignoreFirstLine", false).
          withValue("charset", "UTF-8").
          withValue("commentPrefix", "#").
          withValue("columns", ConfigValueFactory.fromIterable(cols)),
          root, finalCommand, context)

      }

      case f: Parquet => {
        val iConfig = ConfigFactory.empty()
        new ReadAvroParquetFileBuilder().build(iConfig, root, finalCommand, context)
      }

      case f: Avro => {
        val iConfig = ConfigFactory.empty()
        new ReadAvroContainerBuilder().build(iConfig, root, finalCommand, context)
      }

      case _ => throw new UnsupportedOperationException("unsupported  input format for morphline")
    }
  }

  /**
   * Creates a morphline command for writing output according to schedoscope output format
   *
   * @param parent
   * @param transformation
   * @return
   */
  private def createOutput(parent: Command, transformation: MorphlineTransformation): Command = {
    val view = transformation.view.get
    val storageFormat = view.storageFormat.asInstanceOf[ExternalStorageFormat]
    val child = new DropRecordBuilder().build(null, null, null, context);
    val commandConfig = ConfigFactory.empty()
    val fields = if (transformation.definition != "") view.fields.map(field => field.n)
    else view.fields.map(field => "/" + field.n)
    val command = storageFormat match {
      case f: ExaSolution => {
        val schema = view.fields.foldLeft(ConfigFactory.empty())((config, field) => config.withValue((if (transformation.definition == "") "/" else "") + field.n, field.t.runtimeClass.getSimpleName()))
        val oConfig = commandConfig.withValue("connectionURL", f.jdbcUrl).
          withValue("keys", ConfigValueFactory.fromIterable(f.mergeKeys)).
          withValue("merge", f.merge).
          withValue("username", f.userName).
          withValue("schema", schema.root()).
          withValue("targetTable", view.n).
          withValue("password", f.password).
          withValue("fields", ConfigValueFactory.fromIterable(fields))
        new ExasolWriterBuilder().build(oConfig, parent, child, context)
      }

      case f: ExternalTextFile => {
        val oConfig = ConfigFactory.empty().withValue("filename", view.tablePath).
          withValue("separator", f.fieldTerminator).
          withValue("fields", ConfigValueFactory.fromIterable(fields))
        new CSVWriterBuilder().build(oConfig, parent, child, context)
      }

      case f: Redis => {
        log.info("creating RedisWriter")
        val keys = f.keys
        val oConfig = ConfigFactory.empty().withValue("server", f.host).
          withValue("port", f.port).withValue("password", f.password).
          withValue("keys", ConfigValueFactory.fromIterable(keys)).
          withValue("fields", ConfigValueFactory.fromIterable(fields))
        new REDISWriterBuilder().build(oConfig, parent, child, context)
      }

      case f: JDBC => {
        log.info("creating JDBCWriter")
        val schema = view.fields.foldLeft(ConfigFactory.empty())((config, field) => config.withValue(field.n, field.t.runtimeClass.getSimpleName()))
        val oConfig = commandConfig.withValue("connectionURL", f.jdbcUrl).
          withValue("jdbcDriver", f.jdbcDriver).
          withValue("username", f.userName).
          withValue("schema", schema.root()).
          withValue("targetTable",
            view.n).withValue("password", f.password).
            withValue("fields", ConfigValueFactory.fromIterable(fields))
        new JDBCWriterBuilder().build(oConfig, parent, child, context)
      }

      case f: ExternalAvro => {
        log.info("creating AvroFile")
        val oConfig = commandConfig.withValue("filename", view.tablePath + "/000000")
        new AvroWriterBuilder().build(oConfig, parent, child, context)
      }

      case _: NullStorage => {
        child
      }
    }

    command
  }

  /**
   * Inserts a sampling command into the morphline.
   *
   * @param inputConnector
   * @param sample
   * @return
   */
  private def createSampler(inputConnector: CommandConnector, sample: Double) = {
    log.info("creating sampler with rate " + sample)
    val sampleConfig = ConfigFactory.empty().withValue("probability", sample)
    val sampleConnector = new CommandConnector(false, "sampleconnector")
    sampleConnector.setParent(inputConnector.getParent)
    sampleConnector.setChild(inputConnector.getChild)
    val sampleCommand = new SampleBuilder().build(sampleConfig, inputConnector, sampleConnector, context)
    inputConnector.setChild(sampleCommand)
    sampleCommand
  }

  /**
   * Assembles a morphline as defined in the transformation specification
   *
   * @param transformation
   * @return
   */
  private def createMorphline(transformation: MorphlineTransformation): Command = {
    val view = transformation.view.get
    val inputView = transformation.view.get.dependencies.head

    val inputConnector = new CommandConnector(false, "inputconnector")
    val outputConnector = new CommandConnector(false, "outputconnector")

    val inputCommand = createInput(inputView.storageFormat, inputView.n, inputView.fields.map(field => field.n), inputConnector)
    val finalCommand = createOutput(outputConnector, transformation)

    outputConnector.setChild(finalCommand)
    inputConnector.setParent(inputCommand)
    inputConnector.setChild(outputConnector)
    outputConnector.setParent(inputConnector)

    MorphlineClasspathUtil.setupJavaCompilerClasspath()
    // if the user did specify a morphline, plug it between reader and writer
    if (transformation.definition != "") {
      log.info("compiling morphline...")
      val morphlineConfig = ConfigFactory.parseString(transformation.definition)
      val morphline = new PipeBuilder().build(morphlineConfig, inputConnector, outputConnector, context)
      outputConnector.setParent(morphline)
      inputConnector.setChild(morphline)
      inputConnector.parent
    } else {
      inputView.storageFormat match {
        case _: TextFile => inputCommand

        case _ => {
          // if the user did not specify a morphline, at least extract all avro paths so they can be anonymized
          log.info("Empty morphline, inserting extractAvro")
          val extractAvroTreeCommand = new ExtractAvroTreeBuilder().build(ConfigFactory.empty(), inputConnector, outputConnector, context)
          inputConnector.setChild(extractAvroTreeCommand)
          outputConnector.setParent(extractAvroTreeCommand)
          extractAvroTreeCommand
        }
      }

      val sensitive = (view.fields.filter(_.isPrivacySensitive).map(_.n)) ++ transformation.anonymize
      if (!sensitive.isEmpty) {
        log.info("adding anonymizer")
        val anonConfig = ConfigFactory.empty().withValue("fields", ConfigValueFactory.fromIterable(sensitive))

        val output2Connector = new CommandConnector(false, "output2")
        output2Connector.setParent(outputConnector.getParent);
        output2Connector.setChild(outputConnector.getChild())

        val anonymizer = if (view.storageFormat.isInstanceOf[ExternalAvro])
          new AnonymizeAvroBuilder().build(anonConfig, outputConnector, output2Connector, context)
        else
          new AnonymizeBuilder().build(anonConfig, outputConnector, output2Connector, context)
        outputConnector.setChild(anonymizer)
      }

      if (transformation.sampling < 100)
        createSampler(inputConnector, transformation.sampling)
      else
        inputConnector.parent
    }
  }

  /**
   * Calls the morphline with all files in the source view.
   *
   * @param command
   * @param transformation
   * @return
   */
  private def runMorphline(command: Command, transformation: MorphlineTransformation): DriverRunState[MorphlineTransformation] = {
    Notifications.notifyBeginTransaction(command)
    Notifications.notifyStartSession(command)

    val driver = this
    ugi.doAs(new PrivilegedAction[DriverRunState[MorphlineTransformation]]() {
      override def run(): DriverRunState[MorphlineTransformation] = {
        try {
          val view = transformation.view.get
          view.dependencies.foreach { dep =>
            {
              val fs = FileSystem.get(new URI(dep.fullPath), hadoopConf)
              val test = fs.listStatus(new Path(dep.fullPath)).map { status =>
                val record: Record = new Record()
                if (!status.getPath().getName().startsWith("_")) {
                  log.info("processing morphline on " + status.getPath().toUri().toString())
                  val in: java.io.InputStream =
                    fs.open(status.getPath()).getWrappedStream().asInstanceOf[java.io.InputStream]
                  record.put(Fields.ATTACHMENT_BODY, in.asInstanceOf[java.io.InputStream]);
                  for (field <- view.partitionParameters)
                    record.put(field.n, field.v.get)
                  record.put("file_upload_url", status.getPath().toUri().toString())
                  try {
                    if (!command.process(record))
                      log.error("Morphline failed to process record: " + record);
                  } catch {
                    case e: Throwable => {
                      Notifications.notifyRollbackTransaction(command)
                      context.getExceptionHandler().handleException(e, null)

                    }
                  } finally {
                    in.close();
                  }
                }
              }
            }
          }
          Notifications.notifyCommitTransaction(command)
        } catch {
          case e: Throwable => {
            Notifications.notifyRollbackTransaction(command)
            context.getExceptionHandler().handleException(e, null)
            log.error("Morphline failed", e)
            return DriverRunFailed[MorphlineTransformation](driver, s"Morphline failed", e)
          }
        }
        Notifications.notifyShutdown(command);
        DriverRunSucceeded[MorphlineTransformation](driver, "Morphline succeeded")
      }
    })
  }
}

object MorphlineDriver {
  def apply(ds: DriverSettings) = new MorphlineDriver(ds.driverRunCompletionHandlers, Schedoscope.settings.userGroupInformation, Schedoscope.settings.hadoopConf)
}
